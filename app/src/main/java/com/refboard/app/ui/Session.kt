package com.refboard.app.ui

import android.content.Context
import com.refboard.app.RefBoardApp
import com.refboard.app.data.ViewerImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Hand-off for the full-screen viewer, plus app-wide snackbar messages.
 * The viewer list is also written to disk, so the viewer still has its
 * images if Android closes the app while it is in the background.
 */
object Session {
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(ViewerImage.serializer())
    private val app get() = RefBoardApp.instance
    private val file get() = File(app.cacheDir, "viewer.json")
    private val prefs get() = app.getSharedPreferences("viewer", Context.MODE_PRIVATE)

    var viewerImages: List<ViewerImage> = emptyList()
        private set
    var viewerStart: Int = 0
        private set

    fun openViewer(images: List<ViewerImage>, start: Int) {
        viewerImages = images
        viewerStart = start.coerceIn(0, (images.size - 1).coerceAtLeast(0))
        prefs.edit().putInt("page", viewerStart).apply()
        RefBoardApp.appScope.launch(Dispatchers.IO) {
            runCatching {
                val tmp = File(file.parentFile, "viewer.json.tmp")
                tmp.writeText(json.encodeToString(listSerializer, images))
                tmp.renameTo(file)
            }
        }
    }

    /** Remembers the page, so a restored viewer opens where you left it. */
    fun viewerPageChanged(page: Int) {
        viewerStart = page
        prefs.edit().putInt("page", page).apply()
    }

    /** Reloads the last viewer list from disk if memory was cleared. */
    fun restoreViewer() {
        if (viewerImages.isNotEmpty()) return
        val saved = runCatching { json.decodeFromString(listSerializer, file.readText()) }.getOrNull() ?: return
        viewerImages = saved
        viewerStart = prefs.getInt("page", 0).coerceIn(0, (saved.size - 1).coerceAtLeast(0))
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
