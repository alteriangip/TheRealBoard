package com.refboard.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reads the site directly with the app's shared HTTP client: pooled
 * connections and compressed responses, no Python in between. gallery-dl
 * stays as the fallback for anything this doesn't handle.
 */
object SiteApi {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val listings = setOf("popular", "recent", "rating", "likes", "views", "comments")
    private val sampleLimit = Semaphore(4)

    @Serializable
    private data class Row(
        @SerialName("g_url") val galleryUrl: String? = null,
        @SerialName("t_url") val thumb: String? = null,
        @SerialName("t_url_460") val thumb460: String? = null,
        val desc: String? = null,
        val title: String? = null,
        /** Thumbnail height at 300px wide. */
        val h: Int? = null,
    )

    private val rows = ListSerializer(Row.serializer())

    @Serializable
    private data class MenuEntry(val name: String = "", val link: String = "")

    private val menu = ListSerializer(MenuEntry.serializer())

    /**
     * Straight: the home page's category tiles, which come with images.
     * Gay and trans: the site's category menu for that orientation, a mix
     * of categories and tags without images (the screen fetches samples).
     */
    suspend fun categories(o: Orientation): List<Category> {
        if (o == Orientation.Straight) {
            return SiteParser.categories(get(SiteParser.ROOT + "/")).map {
                val slug = it.href.removePrefix(SiteParser.ROOT).trim('/')
                Category(it.title.ifEmpty { slug }, slug, it.href, it.thumb)
            }
        }
        val body = get(
            "${SiteParser.ROOT}/categories-menu?lang=en&orientation=${o.menuCode}&limit=1000",
            accept = "application/json",
        )
        return json.decodeFromString(menu, body).mapNotNull { e ->
            val url = SiteParser.abs(e.link) ?: return@mapNotNull null
            if (e.name.isBlank()) return@mapNotNull null
            Category(SiteParser.unescape(e.name.trim()), url.removePrefix(SiteParser.ROOT).trim('/'), url, null)
        }.distinctBy { it.url }
    }

    suspend fun tagsIndex(o: Orientation): List<TagEntry> =
        SiteParser.tags(get(o.url("tags/"))).map { (name, slug) -> TagEntry(name, slug) }

    /** First gallery thumbnail on a tag page, or null. */
    suspend fun tagSample(url: String): String? = sampleLimit.withPermit {
        SiteParser.galleryTiles(get(url)).firstOrNull { it.thumb != null }?.thumb
    }

    /**
     * One page of galleries for a category, tag, search or listing URL.
     * The first page of a category or tag is HTML; later pages and all
     * search pages come as JSON from the same address.
     */
    suspend fun listingPage(url: String, offset: Int): ListingPage {
        val query = SiteParser.searchQuery(url)
        if (query != null) {
            val params = "q=${enc(query)}&lang=en&limit=20&offset=$offset"
            return jsonPage(get("${SiteParser.ROOT}/search/srch.php?$params", referer = url, json = true), offset)
        }
        if (offset == 0) {
            val items = SiteParser.galleryTiles(get(url)).map { it.toFeedItem() }
            val single = url.trimEnd('/').substringAfterLast('/') in listings
            // Listing pages have only one page of results.
            return ListingPage(items, done = single || items.size < 10, next = items.size)
        }
        val sep = if ('?' in url) '&' else '?'
        return jsonPage(get("$url${sep}limit=20&offset=$offset", referer = url, json = true), offset)
    }

    private fun jsonPage(body: String, offset: Int): ListingPage {
        val list = runCatching { json.decodeFromString(rows, body) }.getOrDefault(emptyList())
        val items = list.mapNotNull { r ->
            val url = SiteParser.abs(r.galleryUrl) ?: return@mapNotNull null
            FeedItem(
                type = "gallery",
                url = url,
                thumb = SiteParser.abs(r.thumb460 ?: r.thumb),
                title = (r.desc ?: r.title ?: "").trim(),
                ratio = r.h?.let { SiteParser.ratio(300f, it.toFloat()) },
            )
        }
        return ListingPage(items, done = list.size < 10, next = offset + list.size)
    }

    /** A gallery's title, tag groups and images, or null if the page didn't parse. */
    suspend fun gallery(url: String): GalleryData? {
        val page = SiteParser.gallery(get(url))
        if (page.images.isEmpty()) return null
        val tags = buildList {
            for (key in listOf("tags", "categories", "models", "channel")) {
                page.groups[key].orEmpty().forEach { if (it !in this) add(it) }
            }
        }
        return GalleryData(
            url = url,
            title = page.title,
            tags = tags,
            groups = page.groups,
            images = page.images.mapIndexed { i, img -> GalleryImage(img.url, i + 1, img.thumb, img.ratio) },
        )
    }

    fun isSiteGallery(url: String) = url.startsWith(SiteParser.ROOT + "/galleries/")

    private fun SiteParser.Tile.toFeedItem() =
        FeedItem(type = "gallery", url = href, thumb = thumb, title = title, ratio = ratio)

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    private suspend fun get(
        url: String,
        referer: String? = null,
        json: Boolean = false,
        accept: String? = null,
    ): String {
        val request = Request.Builder().url(url).apply {
            referer?.let { header("Referer", it) }
            if (json) header("Content-Type", "application/json")
            accept?.let { header("Accept", it) }
        }.build()
        val response = Net.client.newCall(request).await()
        return withContext(Dispatchers.IO) {
            response.use {
                if (!it.isSuccessful) throw IOException("HTTP ${it.code} for $url")
                it.body?.string() ?: ""
            }
        }
    }

    /** Runs the call without blocking a thread; cancelling the coroutine cancels it. */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) = cont.resume(response) { response.close() }
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }
        })
        cont.invokeOnCancellation { runCatching { cancel() } }
    }
}
