package com.refboard.app.data

import com.refboard.app.Refboard
import com.refboard.app.platform.Files
import com.refboard.app.platform.Platform
import com.refboard.app.platform.fileSystem
import com.refboard.app.platform.nowMillis
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json

/**
 * Galleries and links. Site galleries are read by [SiteApi]; anything else
 * goes to gallery-dl where the device has it (Android).
 */
object Bridge {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    // Recently opened galleries, so going back and forth is instant without
    // keeping every gallery of a long session in memory.
    private val galleryCache = Lru<String, GalleryData>(60)
    private val previewLimit = Semaphore(3)

    fun searchUrl(query: String): String = Refboard.site.searchUrl(query)
    fun tagUrl(tag: String): String = Refboard.site.tagUrl(tag)

    /** gallery-dl's version, or null where the device doesn't have it. */
    suspend fun gdlVersion(): String? = Platform.services.galleryDl?.version()

    fun urlFor(source: FeedSource): String = when (source) {
        is FeedSource.Search -> searchUrl(source.query)
        is FeedSource.Tag -> tagUrl(source.tag)
        is FeedSource.Url -> source.url
        is FeedSource.Category -> source.url
        is FeedSource.Listing -> Refboard.site.listingUrl(source.kind, source.orientation)
        is FeedSource.Mix -> error("A mix has no single URL")
    }

    suspend fun gallery(url: String): GalleryData {
        galleryCache[url]?.let { return it }
        readSaved(url)?.let {
            galleryCache[url] = it
            return it
        }
        val site = Refboard.site
        val direct = if (site.isGallery(url)) runCatching { site.gallery(url) }.getOrNull() else null
        val data = direct
            ?: Platform.services.galleryDl?.gallery(url)
            ?: throw SiteException("Couldn't read this gallery")
        galleryCache[url] = data
        save(url, data)
        return data
    }

    // Galleries don't change once posted, so opened ones are kept on disk
    // for a while: reopening one, or pinning it later, needs no request.
    private const val SAVED_DAYS = 14L
    private val savedDir get() = Platform.services.cacheDir / "galleries"

    private fun savedFile(url: String) = savedDir / (url.hashCode().toUInt().toString(16) + "-" + url.length + ".json")

    private suspend fun readSaved(url: String): GalleryData? {
        val file = savedFile(url)
        val age = Files.lastModified(file)?.let { nowMillis() - it } ?: return null
        if (age > SAVED_DAYS * 86_400_000L) return null
        return Files.readTextIo(file)
            ?.let { runCatching { json.decodeFromString(GalleryData.serializer(), it) }.getOrNull() }
            ?.takeIf { it.url == url && it.images.isNotEmpty() }
    }

    private suspend fun save(url: String, data: GalleryData) {
        if (data.images.isEmpty()) return
        runCatching { fileSystem.createDirectories(savedDir) }
        Files.writeTextAtomicIo(savedFile(url), json.encodeToString(GalleryData.serializer(), data))
    }

    fun cachedGallery(url: String): GalleryData? = galleryCache[url]

    /** Cover image for feed tiles that arrived without a thumbnail. */
    suspend fun cover(galleryUrl: String): String? = previewLimit.withPermit {
        gallery(galleryUrl).images.firstOrNull()?.let { it.thumb ?: it.url }
    }

    /** What gallery-dl returns for a link (Android's debug page). */
    suspend fun debug(url: String, limit: Int = 25): String =
        Platform.services.galleryDl?.debug(url, limit) ?: "gallery-dl isn't available on this device."
}

/** A small least-recently-used map. Only used from the main thread. */
class Lru<K, V>(private val max: Int) {
    private val map = LinkedHashMap<K, V>()

    operator fun get(key: K): V? = map.remove(key)?.also { map[key] = it }

    operator fun set(key: K, value: V) {
        map.remove(key)
        map[key] = value
        while (map.size > max) map.remove(map.keys.first())
    }
}
