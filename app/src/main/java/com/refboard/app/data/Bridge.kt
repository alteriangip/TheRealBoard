package com.refboard.app.data

import android.util.LruCache
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.refboard.app.RefBoardApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Kotlin side of refbridge.py (gallery-dl running inside the app). Site
 * galleries are read by [SiteApi] first; gallery-dl handles the rest.
 */
object Bridge {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val py by lazy {
        RefBoardApp.startPython()
        Python.getInstance().getModule("refbridge")
    }

    // Recently opened galleries, so going back and forth is instant without
    // keeping every gallery of a long session in memory.
    private val galleryCache = LruCache<String, GalleryData>(60)
    private val previewLimit = Semaphore(3)

    fun searchUrl(query: String): String = SiteParser.searchUrl(query)
    fun tagUrl(tag: String): String = SiteParser.tagUrl(tag)
    suspend fun gdlVersion(): String = io { py.callAttr("gdl_version").toString() }

    fun urlFor(source: FeedSource): String = when (source) {
        is FeedSource.Search -> searchUrl(source.query)
        is FeedSource.Tag -> tagUrl(source.tag)
        is FeedSource.Url -> source.url
        is FeedSource.Category -> source.url
        is FeedSource.Listing -> source.orientation.url("${source.kind}/")
        is FeedSource.Mix -> error("A mix has no single URL")
    }

    suspend fun startFeed(url: String): Int = io { py.callAttr("feed_start", url).toInt() }

    suspend fun nextPage(feedId: Int, count: Int): FeedPage = io {
        json.decodeFromString(FeedPage.serializer(), py.callAttr("feed_next", feedId, count).toString())
    }

    fun closeFeed(feedId: Int) {
        runCatching { py.callAttr("feed_close", feedId) }
    }

    suspend fun gallery(url: String): GalleryData {
        galleryCache.get(url)?.let { return it }
        readSaved(url)?.let {
            galleryCache.put(url, it)
            return it
        }
        val direct = if (SiteApi.isSiteGallery(url)) runCatching { SiteApi.gallery(url) }.getOrNull() else null
        val data = direct ?: io {
            json.decodeFromString(GalleryData.serializer(), py.callAttr("gallery", url).toString())
        }
        galleryCache.put(url, data)
        save(url, data)
        return data
    }

    // Galleries don't change once posted, so opened ones are kept on disk
    // for a while: reopening one, or pinning it later, needs no request.
    private const val SAVED_DAYS = 14L
    private val savedDir by lazy { java.io.File(RefBoardApp.instance.cacheDir, "galleries").apply { mkdirs() } }

    private fun savedFile(url: String) = java.io.File(savedDir, Integer.toHexString(url.hashCode()) + "-" + url.length + ".json")

    private suspend fun readSaved(url: String): GalleryData? = withContext(Dispatchers.IO) {
        val file = savedFile(url)
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < SAVED_DAYS * 86_400_000L
        if (!fresh) return@withContext null
        runCatching { json.decodeFromString(GalleryData.serializer(), file.readText()) }
            .getOrNull()?.takeIf { it.url == url && it.images.isNotEmpty() }
    }

    private suspend fun save(url: String, data: GalleryData) = withContext(Dispatchers.IO) {
        if (data.images.isEmpty()) return@withContext
        runCatching { savedFile(url).writeText(json.encodeToString(GalleryData.serializer(), data)) }
    }

    fun cachedGallery(url: String): GalleryData? = galleryCache.get(url)

    /** Cover image for feed tiles that arrived without a thumbnail. */
    suspend fun cover(galleryUrl: String): String? = previewLimit.withPermit {
        gallery(galleryUrl).images.firstOrNull()?.let { it.thumb ?: it.url }
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
