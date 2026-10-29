package com.refboard.app.ui

import com.refboard.app.data.ViewerImage
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Hand-off for the full-screen viewer, plus app-wide snackbar messages. */
object Session {
    var viewerImages: List<ViewerImage> = emptyList()
        private set
    var viewerStart: Int = 0
        private set

    fun openViewer(images: List<ViewerImage>, start: Int) {
        viewerImages = images
        viewerStart = start.coerceIn(0, (images.size - 1).coerceAtLeast(0))
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
