package com.refboard.app.data

import android.util.LruCache
import com.chaquo.python.PyException
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Kotlin side of refbridge.py (gallery-dl running inside the app). */
object Bridge {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val py by lazy { Python.getInstance().getModule("refbridge") }

    // Recently opened galleries, so going back and forth is instant without
    // keeping every gallery of a long session in memory.
    private val galleryCache = LruCache<String, GalleryData>(60)
    private val previewLimit = Semaphore(3)

    fun searchUrl(query: String): String = py.callAttr("search_url", query).toString()
    fun tagUrl(tag: String): String = py.callAttr("tag_url", tag).toString()
    suspend fun searchUrlAsync(query: String): String = io { searchUrl(query) }
    suspend fun tagUrlAsync(tag: String): String = io { tagUrl(tag) }
    fun gdlVersion(): String = py.callAttr("gdl_version").toString()

    fun urlFor(source: FeedSource): String = when (source) {
        is FeedSource.Search -> searchUrl(source.query)
        is FeedSource.Tag -> tagUrl(source.tag)
        is FeedSource.Url -> source.url
        is FeedSource.Category -> source.url
        is FeedSource.Listing -> "${Net.ROOT}/${source.kind}/"
        is FeedSource.Mix -> error("A mix has no single URL")
    }

    // ------------------------------------------------ site pages

    private val sampleLimit = Semaphore(3)

    suspend fun categories(): List<Category> = io {
        json.decodeFromString(ListSerializer(Category.serializer()), py.callAttr("categories").toString())
    }

    suspend fun tagsIndex(): List<TagEntry> = io {
        json.decodeFromString(ListSerializer(TagEntry.serializer()), py.callAttr("tags_index").toString())
    }

    suspend fun listingPage(url: String, offset: Int): ListingPage = io {
        json.decodeFromString(ListingPage.serializer(), py.callAttr("listing_page", url, offset).toString())
    }

    /** First gallery thumbnail on a tag page, or null. */
    suspend fun tagSample(url: String): String? = sampleLimit.withPermit {
        io { py.callAttr("tag_sample", url).toString().ifEmpty { null } }
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
        val data = io {
            json.decodeFromString(GalleryData.serializer(), py.callAttr("gallery", url).toString())
        }
        galleryCache.put(url, data)
        return data
    }

    fun cachedGallery(url: String): GalleryData? = galleryCache.get(url)

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
