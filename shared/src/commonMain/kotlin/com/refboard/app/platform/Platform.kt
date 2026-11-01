package com.refboard.app.platform

import androidx.compose.runtime.Composable
import coil3.PlatformContext
import com.refboard.app.data.FeedLoader
import com.refboard.app.data.GalleryData
import io.ktor.client.HttpClient
import okio.FileSystem
import okio.Path

/** Milliseconds since 1970, like System.currentTimeMillis(). */
expect fun nowMillis(): Long

/** The device's file system. */
expect val fileSystem: FileSystem

/** An HTTP client using the platform's own networking. */
expect fun newHttpClient(config: io.ktor.client.HttpClientConfig<*>.() -> Unit): HttpClient

/**
 * What each device provides to the shared app. Set once at startup by the
 * Android Application or the iPad's view controller.
 */
interface PlatformServices {
    /** Where boards, favourites and settings are kept. */
    val dataDir: Path
    /** Where re-downloadable things (galleries, catalog, image cache) go. */
    val cacheDir: Path
    val imageContext: PlatformContext
    /** Where downloaded pictures are saved. */
    val imageSink: ImageSink
    /** gallery-dl, for links the app can't read itself (Android only). */
    val galleryDl: GalleryDl?

    /** Starts a board download in the background (it reports through the shared downloader). */
    fun startBoardDownload(boardId: String)
    fun cancelBoardDownload(boardId: String)

    /** Settings saved by an older version of the app, to carry over once. */
    fun legacySettings(): Map<String, String> = emptyMap()
}

/** Saves downloaded pictures where the user can find them. */
interface ImageSink {
    /** Shown to the user, e.g. "Pictures/RefBoard". */
    val label: String
    /** File names already saved in a board's folder. */
    suspend fun existing(folder: String): Set<String>
    /** Saves a picture; returns an address the image loader can open (for offline viewing). */
    suspend fun write(folder: String, name: String, mime: String, bytes: ByteArray): String
    /** Every picture saved so far, as (file name, address). */
    suspend fun listAll(): List<Pair<String, String>>
}

/** gallery-dl running inside the app, for links the site reader doesn't handle. */
interface GalleryDl {
    suspend fun gallery(url: String): GalleryData
    fun feed(url: String): FeedLoader
    suspend fun debug(url: String, limit: Int): String
    suspend fun version(): String
}

object Platform {
    lateinit var services: PlatformServices
}

/** Opens a picker to save a backup file; calls back with whether it worked. */
@Composable
expect fun rememberBackupExport(onDone: (Boolean) -> Unit): (fileName: String, content: String) -> Unit

/** Opens a picker to choose a backup file; calls back with its text (null if cancelled or unreadable). */
@Composable
expect fun rememberBackupImport(onText: (String?) -> Unit): () -> Unit

/** Asks to show notifications if the platform needs that, then runs the action either way. */
@Composable
expect fun rememberNotificationPermission(): (then: () -> Unit) -> Unit

/**
 * Plays a video inside the app, sending [referer] with the request. Some
 * sites (Erome) refuse their video files without it, so handing the link
 * to a browser or another app doesn't work.
 */
@Composable
expect fun VideoPlayer(url: String, referer: String, modifier: androidx.compose.ui.Modifier)
