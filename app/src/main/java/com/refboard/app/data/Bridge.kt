package com.refboard.app.data

import com.chaquo.python.PyException
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/** Kotlin side of refbridge.py (gallery-dl running inside the app). */
object Bridge {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val py by lazy { Python.getInstance().getModule("refbridge") }

    private val galleryCache = ConcurrentHashMap<String, GalleryData>()
    private val previewLimit = Semaphore(3)

    fun searchUrl(query: String): String = py.callAttr("search_url", query).toString()
    fun tagUrl(tag: String): String = py.callAttr("tag_url", tag).toString()
    fun gdlVersion(): String = py.callAttr("gdl_version").toString()

    fun urlFor(source: FeedSource): String = when (source) {
        is FeedSource.Search -> searchUrl(source.query)
        is FeedSource.Tag -> tagUrl(source.tag)
        is FeedSource.Url -> source.url
    }

    suspend fun startFeed(url: String): Int = io { py.callAttr("feed_start", url).toInt() }

    suspend fun nextPage(feedId: Int, count: Int): FeedPage = io {
        json.decodeFromString(FeedPage.serializer(), py.callAttr("feed_next", feedId, count).toString())
    }

    fun closeFeed(feedId: Int) {
        runCatching { py.callAttr("feed_close", feedId) }
    }

    suspend fun gallery(url: String): GalleryData {
        galleryCache[url]?.let { return it }
        val data = io {
            json.decodeFromString(GalleryData.serializer(), py.callAttr("gallery", url).toString())
        }
        galleryCache[url] = data
        return data
    }

    fun cachedGallery(url: String): GalleryData? = galleryCache[url]

    /** Cover image for feed tiles that arrived without a thumbnail. */
    suspend fun cover(galleryUrl: String): String? = previewLimit.withPermit {
        gallery(galleryUrl).images.firstOrNull()?.url
    }

    suspend fun debug(url: String, limit: Int = 25): String = io {
        py.callAttr("debug", url, limit).toString()
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: PyException) {
            throw BridgeException(cleanMessage(e.message))
        }
    }

    private fun cleanMessage(raw: String?): String {
        val text = raw ?: return "Unknown error"
        // Python errors arrive as "ExceptionType: message"; keep the message.
        return text.lineSequence().firstOrNull()?.substringAfter(": ", text)?.take(300) ?: text
    }
}

class BridgeException(message: String) : Exception(message)
