package com.refboard.app.platform

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import coil3.PlatformContext
import com.refboard.app.Refboard
import com.refboard.app.data.DownloadState
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath

actual fun nowMillis(): Long = System.currentTimeMillis()

actual val fileSystem: FileSystem = FileSystem.SYSTEM

actual fun newHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(OkHttp, config)

class AndroidServices(
    private val app: Application,
    override val galleryDl: GalleryDl?,
) : PlatformServices {
    override val dataDir: Path = app.filesDir.toOkioPath()
    override val cacheDir: Path = app.cacheDir.toOkioPath()
    override val imageContext: PlatformContext = app
    override val imageSink: ImageSink = MediaStoreSink(app)

    init {
        // The image cache used to live here (older image library); it is rebuilt elsewhere.
        app.cacheDir.resolve("images").deleteRecursively()
    }

    private val work get() = WorkManager.getInstance(app)

    override fun startBoardDownload(boardId: String) {
        val request = OneTimeWorkRequestBuilder<BoardDownloadWorker>()
            .setInputData(workDataOf(BoardDownloadWorker.BOARD_ID to boardId))
            .build()
        work.enqueueUniqueWork(workName(boardId), ExistingWorkPolicy.KEEP, request)
    }

    override fun cancelBoardDownload(boardId: String) {
        work.cancelUniqueWork(workName(boardId))
    }

    private fun workName(boardId: String) = "download-$boardId"

    /** Recent searches and the content setting, from before they moved into shared storage. */
    override fun legacySettings(): Map<String, String> {
        val sp = app.getSharedPreferences("refboard", Context.MODE_PRIVATE)
        return sp.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
    }
}

/** Saves into Pictures/RefBoard/<folder> so pictures show up in the gallery app. */
private class MediaStoreSink(private val context: Context) : ImageSink {
    override val label = "Pictures/RefBoard"

    override suspend fun existing(folder: String): Set<String> = withContext(Dispatchers.IO) {
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
        names
    }

    override suspend fun write(folder: String, name: String, mime: String, bytes: ByteArray): String =
        withContext(Dispatchers.IO) {
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
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("could not open file")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            uri.toString()
        }

    override suspend fun listAll(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val out = ArrayList<Pair<String, String>>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("Pictures/RefBoard/%"),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                    out += name to uri.toString()
                }
            }
        }
        out
    }
}

/** Runs a board download in the background with a progress notification. */
class BoardDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val notificationId = params.id.hashCode()

    override suspend fun doWork(): Result {
        val boardId = inputData.getString(BOARD_ID) ?: return Result.failure()
        val name = Refboard.boards.get(boardId)?.name ?: return Result.failure()
        // If Android won't allow a foreground job right now, still try in the background.
        runCatching { setForeground(foregroundInfo(name, null)) }
        var lastShown = 0L
        Refboard.downloader.runBoard(boardId) { state ->
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

@Composable
actual fun rememberBackupExport(onDone: (Boolean) -> Unit): (fileName: String, content: String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Held across recompositions while the picker is open.
    val pending = remember { arrayOfNulls<String>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = pending[0]
        pending[0] = null
        if (uri == null || text == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
                }.isSuccess
            }
            onDone(ok)
        }
    }
    return { fileName, content ->
        pending[0] = content
        launcher.launch(fileName)
    }
}

@Composable
actual fun rememberBackupImport(onText: (String?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            onText(null)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                }.getOrNull()
            }
            onText(text)
        }
    }
    return { launcher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
}

@Composable
actual fun rememberNotificationPermission(): (then: () -> Unit) -> Unit {
    val context = LocalContext.current
    val after = remember { arrayOfNulls<() -> Unit>(1) }
    // Asked once so the download progress shows; the action runs either way.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        after[0]?.invoke()
        after[0] = null
    }
    return { then ->
        val needsAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsAsk) {
            after[0] = then
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            then()
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
actual fun VideoPlayer(url: String, referer: String, modifier: androidx.compose.ui.Modifier) {
    val context = LocalContext.current
    val player = remember(url) {
        // Sends the site's Referer and the app's user agent with every video request.
        val http = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent(com.refboard.app.data.Net.UA)
            .setDefaultRequestProperties(mapOf("Referer" to referer))
            .setAllowCrossProtocolRedirects(true)
        androidx.media3.exoplayer.ExoPlayer.Builder(context)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(http))
            .build()
            .apply {
                setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
                prepare()
                playWhenReady = true
            }
    }
    androidx.compose.runtime.DisposableEffect(player) { onDispose { player.release() } }
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx -> androidx.media3.ui.PlayerView(ctx).apply { this.player = player } },
        modifier = modifier,
    )
}
