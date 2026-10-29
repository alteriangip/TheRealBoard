package com.refboard.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.refboard.app.RefBoardApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request

data class DownloadState(
    val total: Int,
    val done: Int = 0,
    /** Already in the folder from an earlier download; counted in [done]. */
    val skipped: Int = 0,
    val failed: Int = 0,
    val finished: Boolean = false,
    val cancelled: Boolean = false,
) {
    val handled get() = done + failed
}

/**
 * Saves images into Pictures/RefBoard/<folder> so they show up in the gallery app.
 * Board downloads run as background work, so they keep going if the app is closed.
 */
class Downloader(private val context: Context) {

    /** Keyed by board id. */
    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states

    private val work get() = WorkManager.getInstance(context)

    fun downloadBoard(board: Board) {
        if (_states.value[board.id]?.finished == false) return
        _states.update { it + (board.id to DownloadState(total = board.pins.size)) }
        val request = OneTimeWorkRequestBuilder<BoardDownloadWorker>()
            .setInputData(workDataOf(BoardDownloadWorker.BOARD_ID to board.id))
            .build()
        work.enqueueUniqueWork(workName(board.id), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(boardId: String) {
        work.cancelUniqueWork(workName(boardId))
    }

    fun clear(key: String) = _states.update { it - key }

    suspend fun saveOne(url: String, folder: String = "Saved"): Boolean =
        withContext(Dispatchers.IO) { runCatching { save(url, safeName(folder)) }.isSuccess }

    /** Does the actual board download; called from [BoardDownloadWorker]. */
    internal suspend fun runBoard(boardId: String, onProgress: suspend (DownloadState) -> Unit) {
        val board = RefBoardApp.boards.get(boardId) ?: return
        val urls = board.pins.map { it.imageUrl }.distinct()
        val folder = safeName(board.name)
        _states.update { it + (boardId to DownloadState(total = urls.size)) }

        fun bump(transform: (DownloadState) -> DownloadState): DownloadState? {
            var now: DownloadState? = null
            _states.update { m ->
                val s = m[boardId] ?: return@update m
                transform(s).also { now = it }.let { m + (boardId to it) }
            }
            return now
        }

        try {
            withContext(Dispatchers.IO) {
                val existing = existingNames(folder)
                val limit = Semaphore(4)
                coroutineScope {
                    urls.map { url ->
                        async {
                            limit.withPermit {
                                val state = when {
                                    fileName(url) in existing -> bump { it.copy(done = it.done + 1, skipped = it.skipped + 1) }
                                    runCatching { save(url, folder) }.isSuccess -> bump { it.copy(done = it.done + 1) }
                                    else -> bump { it.copy(failed = it.failed + 1) }
                                }
                                state?.let { onProgress(it) }
                            }
                        }
                    }.awaitAll()
                }
            }
            bump { it.copy(finished = true) }
        } catch (e: CancellationException) {
            bump { it.copy(finished = true, cancelled = true) }
            throw e
        }
    }

    /** Names already saved in this folder, so a second download skips them. */
    private fun existingNames(folder: String): Set<String> {
        val names = HashSet<String>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
                "${MediaStore.Images.Media.RELATIVE_PATH} = ?",
                arrayOf("Pictures/RefBoard/$folder/"),
                null,
            )?.use { c -> while (c.moveToNext()) c.getString(0)?.let(names::add) }
        }
        return names
    }

    private fun save(url: String, folder: String) {
        val request = Request.Builder().url(url).build()
        Net.client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body ?: error("empty body")
            val name = fileName(url)
            val mime = body.contentType()?.toString()?.substringBefore(';') ?: mimeFor(name)

            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RefBoard/$folder")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("could not create file")
            try {
                resolver.openOutputStream(uri)?.use { out -> body.byteStream().copyTo(out) }
                    ?: error("could not open file")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        }
    }

    private fun fileName(url: String) = url.substringBefore('?').substringAfterLast('/').ifEmpty { "image.jpg" }

    private fun mimeFor(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    private fun safeName(name: String) =
        name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifEmpty { "Board" }.take(60)

    private fun workName(boardId: String) = "download-$boardId"
}

/** Runs a board download in the background with a progress notification. */
class BoardDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val notificationId = params.id.hashCode()

    override suspend fun doWork(): Result {
        val boardId = inputData.getString(BOARD_ID) ?: return Result.failure()
        val name = RefBoardApp.boards.get(boardId)?.name ?: return Result.failure()
        // If Android won't allow a foreground job right now, still try in the background.
        runCatching { setForeground(foregroundInfo(name, null)) }
        var lastShown = 0L
        RefBoardApp.downloader.runBoard(boardId) { state ->
            val now = System.currentTimeMillis()
            if (now - lastShown > 500) {
                lastShown = now
                runCatching { setForeground(foregroundInfo(name, state)) }
            }
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("board", null)

    private fun foregroundInfo(boardName: String, state: DownloadState?): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Board downloads", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Saving $boardName")
            .setContentText(state?.let { "${it.handled} of ${it.total}" } ?: "Starting…")
            .setProgress(state?.total ?: 0, state?.handled ?: 0, state == null)
            .setOngoing(true)
            .setSilent(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
            )
            .build()
        return ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val BOARD_ID = "board_id"
        private const val CHANNEL = "downloads"
    }
}
