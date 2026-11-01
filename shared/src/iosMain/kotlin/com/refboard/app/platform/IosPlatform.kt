@file:OptIn(ExperimentalForeignApi::class)

package com.refboard.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import coil3.PlatformContext
import com.refboard.app.Refboard
import com.refboard.app.ui.Session
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSDate
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSSearchPathDirectory
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSUserDomainMask
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskInvalid

actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

actual val fileSystem: FileSystem = FileSystem.SYSTEM

actual fun newHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(Darwin, config)

private fun systemDir(kind: NSSearchPathDirectory): Path {
    val path = NSSearchPathForDirectoriesInDomains(kind, NSUserDomainMask, true).first() as String
    return path.toPath().also { runCatching { fileSystem.createDirectories(it) } }
}

/** The app's folder in the Files app ("On My iPad › RefBoard"). */
internal val documentsDir: Path get() = systemDir(NSDocumentDirectory)

class IosServices : PlatformServices {
    override val dataDir: Path = systemDir(NSApplicationSupportDirectory)
    override val cacheDir: Path = systemDir(NSCachesDirectory)
    override val imageContext: PlatformContext = PlatformContext.INSTANCE
    override val imageSink: ImageSink = FilesSink(documentsDir / "Pictures")
    override val galleryDl: GalleryDl? = null

    private val jobs = HashMap<String, Job>()

    override fun startBoardDownload(boardId: String) {
        if (jobs[boardId]?.isActive == true) return
        // On the main thread, so the job list is only touched from one place.
        jobs[boardId] = Refboard.appScope.launch(Dispatchers.Main) {
            // Ask iPadOS for time to finish if the app is sent to the background.
            val app = UIApplication.sharedApplication
            var task = UIBackgroundTaskInvalid
            task = app.beginBackgroundTaskWithExpirationHandler {
                app.endBackgroundTask(task)
                task = UIBackgroundTaskInvalid
            }
            try {
                Refboard.downloader.runBoard(boardId) {}
            } finally {
                if (task != UIBackgroundTaskInvalid) app.endBackgroundTask(task)
                jobs.remove(boardId)
            }
        }
    }

    override fun cancelBoardDownload(boardId: String) {
        jobs.remove(boardId)?.cancel()
    }
}

/** Saves pictures as files under the app's folder, visible in the Files app. */
private class FilesSink(private val root: Path) : ImageSink {
    override val label = "Files › RefBoard › Pictures"

    override suspend fun existing(folder: String): Set<String> = withContext(Dispatchers.IO) {
        runCatching { fileSystem.list(root / folder).map { it.name }.toSet() }.getOrDefault(emptySet())
    }

    override suspend fun write(folder: String, name: String, mime: String, bytes: ByteArray): String =
        withContext(Dispatchers.IO) {
            val dir = root / folder
            fileSystem.createDirectories(dir)
            val file = dir / name
            fileSystem.write(file) { write(bytes) }
            "file://$file"
        }

    override suspend fun listAll(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        runCatching {
            fileSystem.listRecursively(root)
                .filter { fileSystem.metadataOrNull(it)?.isRegularFile == true }
                .map { it.name to "file://$it" }
                .toList()
        }.getOrDefault(emptyList())
    }
}

private val backupsDir: Path get() = documentsDir / "Backups"

/** Backups go to Files › RefBoard › Backups, where they can be shared or copied off. */
@Composable
actual fun rememberBackupExport(onDone: (Boolean) -> Unit): (fileName: String, content: String) -> Unit =
    remember {
        { fileName, content ->
            Refboard.appScope.launch {
                val ok = runCatching { Files.writeTextAtomic(backupsDir / fileName, content) }.isSuccess
                withContext(Dispatchers.Main) {
                    onDone(ok)
                    if (ok) Session.toast("Saved in Files › RefBoard › Backups")
                }
            }
        }
    }

/** Restores the newest backup in Files › RefBoard (or its Backups folder). */
@Composable
actual fun rememberBackupImport(onText: (String?) -> Unit): () -> Unit =
    remember {
        {
            Refboard.appScope.launch {
                val newest = withContext(Dispatchers.IO) {
                    listOf(backupsDir, documentsDir)
                        .flatMap { dir -> runCatching { fileSystem.list(dir) }.getOrDefault(emptyList()) }
                        .filter { it.name.endsWith(".json") }
                        .maxByOrNull { Files.lastModified(it) ?: 0L }
                }
                val text = newest?.let { Files.readTextIo(it) }
                withContext(Dispatchers.Main) {
                    if (newest == null) Session.toast("Put a backup .json file in Files › RefBoard first")
                    onText(text)
                }
            }
        }
    }

/** iPadOS needs no permission for the download progress shown in the app. */
@Composable
actual fun rememberNotificationPermission(): (then: () -> Unit) -> Unit = remember { { then -> then() } }

@Composable
actual fun VideoPlayer(url: String, referer: String, modifier: androidx.compose.ui.Modifier) {
    val controller = remember(url) {
        // AVURLAssetHTTPHeaderFieldsKey: extra headers for every request of this video.
        val headers = mapOf<Any?, Any?>("Referer" to referer, "User-Agent" to com.refboard.app.data.Net.UA)
        val asset = platform.AVFoundation.AVURLAsset(
            uRL = platform.Foundation.NSURL(string = url),
            options = mapOf<Any?, Any?>("AVURLAssetHTTPHeaderFieldsKey" to headers),
        )
        val player = platform.AVFoundation.AVPlayer(playerItem = platform.AVFoundation.AVPlayerItem(asset = asset))
        platform.AVKit.AVPlayerViewController().apply {
            this.player = player
            player.play()
        }
    }
    androidx.compose.runtime.DisposableEffect(controller) { onDispose { controller.player?.pause() } }
    androidx.compose.ui.viewinterop.UIKitViewController(
        factory = { controller },
        modifier = modifier,
    )
}
