package com.refboard.app.data

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

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
        if (query != null && offset > 0) return searchJson(query, offset, referer = url)
        // Channel pages only have one HTML page; continue like gallery-dl, by name.
        if (offset > 0 && "/channels/" in url) {
            val name = url.trimEnd('/').substringAfterLast('/').replace('-', ' ')
            return searchJson(name, offset - FIRST_PAGE, referer = url).let { it.copy(next = it.next + FIRST_PAGE) }
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

    private const val FIRST_PAGE = 20

    private suspend fun searchJson(query: String, offset: Int, referer: String): ListingPage {
        val params = "q=${enc(query)}&lang=en&limit=20&offset=$offset"
        return jsonPage(get("${SiteParser.ROOT}/search/srch.php?$params", referer = referer, json = true), offset)
    }

    /**
     * Searches like the site's search box: a query that matches a tag,
     * category or channel is sent to that page (e.g. "frottage" opens
     * /tags/frottage/); anything else gets the site's own results page.
     */
    suspend fun startSearch(query: String): SearchStart {
        val (finalUrl, body) = getFollowing(SiteParser.searchUrl(query))
        val items = SiteParser.galleryTiles(body).map { it.toFeedItem() }
        val redirected = SiteParser.searchQuery(finalUrl) == null
        return SearchStart(
            url = if (redirected) finalUrl else SiteParser.searchUrl(query),
            redirected = redirected,
            page = ListingPage(items, done = items.size < 10, next = items.size),
        )
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

    private fun enc(s: String) = SiteParser.encodeComponent(s)

    private suspend fun get(
        url: String,
        referer: String? = null,
        json: Boolean = false,
        accept: String? = null,
    ): String = getFollowing(url, referer, json, accept).second

    /** Fetches a page, following redirects; returns the final address and the body. */
    private suspend fun getFollowing(
        url: String,
        referer: String? = null,
        json: Boolean = false,
        accept: String? = null,
    ): Pair<String, String> {
        val response = Net.client.get(url) {
            referer?.let { header(HttpHeaders.Referrer, it) }
            if (json) header(HttpHeaders.ContentType, "application/json")
            accept?.let { header(HttpHeaders.Accept, it) }
        }
        if (!response.status.isSuccess()) throw SiteException("HTTP ${response.status.value} for $url")
        return response.call.request.url.toString() to response.bodyAsText()
    }
}

class SiteException(message: String) : Exception(message)
