package com.refboard.app.ui

import com.refboard.app.Refboard
import com.refboard.app.data.ViewerImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import com.refboard.app.platform.Files
import com.refboard.app.platform.Platform

/**
 * Hand-off for the full-screen viewer, plus app-wide snackbar messages.
 * The viewer list is also written to disk, so the viewer still has its
 * images if Android closes the app while it is in the background.
 */
object Session {
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(ViewerImage.serializer())
    private val file get() = Platform.services.cacheDir / "viewer.json"

    var viewerImages: List<ViewerImage> = emptyList()
        private set
    var viewerStart: Int = 0
        private set

    fun openViewer(images: List<ViewerImage>, start: Int) {
        viewerImages = images
        viewerStart = start.coerceIn(0, (images.size - 1).coerceAtLeast(0))
        Refboard.prefs.putInt("viewer_page", viewerStart)
        Refboard.appScope.launch(Dispatchers.IO) {
            runCatching { Files.writeTextAtomic(file, json.encodeToString(listSerializer, images)) }
        }
    }

    /** Remembers the page, so a restored viewer opens where you left it. */
    fun viewerPageChanged(page: Int) {
        viewerStart = page
        Refboard.prefs.putInt("viewer_page", page)
    }

    /** Reloads the last viewer list from disk if memory was cleared. */
    fun restoreViewer() {
        if (viewerImages.isNotEmpty()) return
        val saved = runCatching { json.decodeFromString(listSerializer, Files.readText(file)!!) }.getOrNull() ?: return
        viewerImages = saved
        viewerStart = Refboard.prefs.getInt("viewer_page", 0).coerceIn(0, (saved.size - 1).coerceAtLeast(0))
    }

    private val _scrollTop = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** "Scroll to the top": the grid on screen listens and scrolls. */
    val scrollTop: SharedFlow<Unit> = _scrollTop

    fun scrollToTop() {
        _scrollTop.tryEmit(Unit)
    }

    private val _messages = MutableSharedFlow<String>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<String> = _messages

    fun toast(text: String) {
        _messages.tryEmit(text)
    }
}
