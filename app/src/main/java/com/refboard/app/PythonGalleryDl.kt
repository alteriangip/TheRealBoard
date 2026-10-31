package com.refboard.app

import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.refboard.app.data.FeedLoader
import com.refboard.app.data.FeedPage
import com.refboard.app.data.GalleryData
import com.refboard.app.platform.GalleryDl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** gallery-dl running inside the app (refbridge.py), for links the site reader doesn't handle. */
class PythonGalleryDl(private val app: android.app.Application) : GalleryDl {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val py by lazy {
        start()
        Python.getInstance().getModule("refbridge")
    }

    /** Starting Python takes a moment; done off the main thread at launch. */
    @Synchronized
    fun start() {
        if (!Python.isStarted()) Python.start(AndroidPlatform(app))
    }

    override suspend fun version(): String = io { py.callAttr("gdl_version").toString() }

    override suspend fun gallery(url: String): GalleryData = io {
        json.decodeFromString(GalleryData.serializer(), py.callAttr("gallery", url).toString())
    }

    override suspend fun debug(url: String, limit: Int): String = io {
        py.callAttr("debug", url, limit).toString()
    }

    override fun feed(url: String): FeedLoader = object : FeedLoader {
        private var feedId: Int? = null

        override suspend fun next(count: Int): FeedPage {
            val id = feedId ?: io { py.callAttr("feed_start", url).toInt() }.also { feedId = it }
            return io { json.decodeFromString(FeedPage.serializer(), py.callAttr("feed_next", id, count).toString()) }
        }

        override fun close() {
            feedId?.let { id -> runCatching { py.callAttr("feed_close", id) } }
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: PyException) {
            throw GalleryDlException(cleanMessage(e.message))
        }
    }

    private fun cleanMessage(raw: String?): String {
        val text = raw ?: return "Unknown error"
        // Python errors arrive as "ExceptionType: message"; keep the message.
        return text.lineSequence().firstOrNull()?.substringAfter(": ", text)?.take(300) ?: text
    }
}

class GalleryDlException(message: String) : Exception(message)
