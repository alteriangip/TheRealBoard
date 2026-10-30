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
import coil.Coil
import com.refboard.app.RefBoardApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.Request
import kotlin.random.Random

data class DownloadState(
    val total: Int,
    val done: Int = 0,
    /** Already in the folder from an earlier download; counted in [done]. */
    val skipped: Int = 0,
    val failed: Int = 0,
    val finished: Boolean = false,
    val cancelled: Boolean = false,
    /** Copied from images the app had already loaded; counted in [done]. */
    val fromCache: Int = 0,
    /** Set while waiting out the site's rate limit. */
    val pausedUntil: Long? = null,
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
        withContext(Dispatchers.IO) {
            val dir = safeName(folder)
            saveFromCache(url, dir) || runCatching { save(url, dir) }.isSuccess
        }

    /** True while any board download is running; browsing prefetch backs off meanwhile. */
    val active: Boolean get() = _states.value.values.any { !it.finished }

    /**
     * Does the actual board download; called from [BoardDownloadWorker].
     *
     * Images the app already has in its image cache are copied from there
     * with no request at all. The rest are fetched two at a time with a
     * short random pause after each (about 2 images a second, so 2,000
     * take 15-20 minutes) to stay under the site's rate limit. If the site
     * pushes back anyway (429/403/503), every worker waits it out, longer
     * each time, and then retries the same image.
     */
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

        val gate = RateGate()
        try {
            withContext(Dispatchers.IO) {
                val existing = existingNames(folder)
                val queue = Channel<String>(Channel.UNLIMITED)
                urls.forEach { queue.trySend(it) }
                queue.close()
                coroutineScope {
                    repeat(WORKERS) {
                        launch {
                            for (url in queue) {
                                val state = when {
                                    fileName(url) in existing ->
                                        bump { it.copy(done = it.done + 1, skipped = it.skipped + 1) }
                                    saveFromCache(url, folder) ->
                                        bump { it.copy(done = it.done + 1, fromCache = it.fromCache + 1) }
                                    fetchWithBackoff(url, folder, gate) { until ->
                                        bump { it.copy(pausedUntil = until) }?.let { s -> onProgress(s) }
                                    } -> bump { it.copy(done = it.done + 1, pausedUntil = null) }
                                    else -> bump { it.copy(failed = it.failed + 1) }
                                }
                                state?.let { onProgress(it) }
                            }
                        }
                    }
                }
            }
            bump { it.copy(finished = true, pausedUntil = null) }
        } catch (e: CancellationException) {
            bump { it.copy(finished = true, cancelled = true, pausedUntil = null) }
            throw e
        }
    }

    /** Shared by a download's workers: when the site says slow down, everyone waits. */
    private class RateGate {
        @Volatile var pausedUntil = 0L
        @Volatile var backoffMs = 30_000L

        suspend fun awaitOpen() {
            while (true) {
                val wait = pausedUntil - System.currentTimeMillis()
                if (wait <= 0) return
                delay(wait)
            }
        }

        /** Starts (or extends) a pause; returns when it ends. */
        fun pause(retryAfterMs: Long?): Long {
            val now = System.currentTimeMillis()
            val length = retryAfterMs?.coerceIn(5_000L, 15 * 60_000L) ?: backoffMs
            val until = now + length
            if (until > pausedUntil) {
                pausedUntil = until
                backoffMs = (backoffMs * 2).coerceAtMost(10 * 60_000L)
            }
            return pausedUntil
        }

        fun succeeded() {
            backoffMs = 30_000L
        }
    }

    private class RateLimited(val retryAfterMs: Long?) : Exception("rate limited")

    /** Fetches one image, waiting out rate limits. False if it still fails. */
    private suspend fun fetchWithBackoff(
        url: String,
        folder: String,
        gate: RateGate,
        onPause: suspend (Long?) -> Unit,
    ): Boolean {
        repeat(MAX_ATTEMPTS) {
            gate.awaitOpen()
            try {
                save(url, folder)
                gate.succeeded()
                delay(Random.nextLong(250, 750))
                return true
            } catch (e: RateLimited) {
                onPause(gate.pause(e.retryAfterMs))
                gate.awaitOpen()
                onPause(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Network hiccup or a missing image: one quick retry, then give up.
                if (it >= 1) return false
                delay(1_000)
            }
        }
        return false
    }

    /** Copies an image the app already downloaded for viewing; no request needed. */
    private fun saveFromCache(url: String, folder: String): Boolean = runCatching {
        val cache = Coil.imageLoader(context).diskCache ?: return false
        cache.openSnapshot(url)?.use { snap ->
            val file = snap.data.toFile()
            if (file.length() == 0L) return false
            write(fileName(url), mimeFor(fileName(url)), folder) { out -> file.inputStream().use { it.copyTo(out) } }
            true
        } ?: false
    }.getOrDefault(false)

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
            if (resp.code == 429 || resp.code == 403 || resp.code == 503) {
                val retryAfter = resp.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000)
                throw RateLimited(retryAfter)
            }
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body ?: error("empty body")
            val name = fileName(url)
            val mime = body.contentType()?.toString()?.substringBefore(';') ?: mimeFor(name)
            write(name, mime, folder) { out -> body.byteStream().copyTo(out) }
        }
    }

    /** Creates the file in Pictures/RefBoard/<folder>; removes it again if writing fails. */
    private fun write(name: String, mime: String, folder: String, copy: (java.io.OutputStream) -> Unit) {
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
            resolver.openOutputStream(uri)?.use(copy) ?: error("could not open file")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            LocalImages.put(name, uri)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
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

    private companion object {
        const val WORKERS = 2
        const val MAX_ATTEMPTS = 8
    }
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
            if (now - lastShown > 500 || state.pausedUntil != null) {
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
            .setContentText(
                when {
                    state == null -> "Starting…"
                    state.pausedUntil != null -> {
                        val secs = ((state.pausedUntil - System.currentTimeMillis()) / 1000).coerceAtLeast(1)
                        "${state.handled} of ${state.total} · site asked to slow down, resuming in ${secs}s"
                    }
                    else -> "${state.handled} of ${state.total}"
                },
            )
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
