package com.refboard.app.data

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay

/** erome.com: albums of photos and videos, searched by words and tags. */
object EromeSite : Site {
    override val id = "erome"
    override val appName = "EroBoard"
    override val root = EromeParser.ROOT
    override val listings = listOf("explore" to "Explore", "new" to "Newest")

    override fun listingUrl(kind: String, orientation: Orientation) =
        if (kind == "new") "$root/explore/new" else "$root/explore"

    /** Explore, search and user pages all list albums the same way. */
    override fun isListingUrl(url: String) = url.startsWith(root) && !isGallery(url)
    override val hasOrientation = false
    override val hasTagIndex = false
    override val categoriesTitle = "Popular tags"
    override val peopleLabel = "Uploaders"
    override val hasChannels = false
    override val starterSearches = listOf(
        "standing", "sitting", "kneeling", "lying", "from behind", "side view",
        "mirror", "legs", "feet", "stretching", "yoga", "looking back", "outdoor",
    )

    override fun searchUrl(query: String) = EromeParser.searchUrl(query)
    /** Erome tags are searches. */
    override fun tagUrl(tag: String) = EromeParser.searchUrl(tag)
    override fun personUrl(name: String) = EromeParser.userUrl(name)
    override fun isGallery(url: String) = EromeParser.isAlbum(url)
    override fun isImageHost(host: String) = Regex("""^[sv]\d+\.erome\.com$""").matches(host)
    override fun smallImage(url: String) = EromeParser.thumbOf(url)

    override suspend fun startSearch(query: String): SearchStart {
        val url = searchUrl(query)
        return SearchStart(url, redirected = false, page = listingPage(url, 0))
    }

    /** [offset] counts pages already read (0 for the first). */
    override suspend fun listingPage(url: String, offset: Int): ListingPage {
        val albums = EromeParser.albums(get(EromeParser.pageUrl(url, offset + 1)))
        val items = albums.map { a ->
            FeedItem(
                type = "gallery",
                url = a.url,
                thumb = a.thumb,
                title = a.title,
                // Album thumbnails are square.
                ratio = 1f,
            )
        }
        return ListingPage(items, done = albums.size < EromeParser.PAGE_SIZE, next = offset + 1)
    }

    override suspend fun gallery(url: String): GalleryData? {
        val page = EromeParser.album(get(url))
        if (page.media.isEmpty()) return null
        val groups = buildMap {
            if (page.user.isNotEmpty()) put("models", listOf(page.user))
            if (page.tags.isNotEmpty()) put("tags", page.tags)
        }
        return GalleryData(
            url = url,
            title = page.title,
            tags = page.tags,
            groups = groups,
            images = page.media.mapIndexed { i, m ->
                GalleryImage(
                    url = m.url,
                    num = i + 1,
                    thumb = if (m.video != null) m.url else EromeParser.thumbOf(m.url),
                    ratio = m.ratio,
                    video = m.video,
                )
            },
        )
    }

    /** Erome has no category list; these common tags stand in, with sample images fetched as they scroll by. */
    override suspend fun categories(orientation: Orientation): List<Category> = popularTags.map {
        Category(name = it, slug = SiteParser.slug(it), url = searchUrl(it), thumb = null)
    }

    override suspend fun tagsIndex(orientation: Orientation): List<TagEntry> = emptyList()

    private val popularTags = listOf(
        "amateur", "selfie", "mirror", "outdoor", "beach", "bikini", "lingerie", "stockings",
        "cosplay", "yoga", "fitness", "gym", "dance", "shower", "bath", "bed",
        "legs", "feet", "booty", "abs", "tattoo", "curvy", "petite", "athletic",
        "blonde", "brunette", "redhead", "asian", "latina", "ebony", "milf", "couple",
        "boudoir", "nude", "topless", "pov", "solo", "art",
    )

    /**
     * Erome sometimes shows a short "Please wait a few moments" check page
     * first; like gallery-dl, wait and ask again (cookies are kept).
     */
    private suspend fun get(url: String): String {
        repeat(5) { attempt ->
            val response = Net.client.get(url) { header(HttpHeaders.Referrer, "$root/") }
            if (!response.status.isSuccess()) throw SiteException("HTTP ${response.status.value} for $url")
            val body = response.bodyAsText()
            if ("<title>Please wait a few moments</title>" !in body.take(800)) return body
            delay(5_000L + attempt * 1_000L)
        }
        throw SiteException("Erome is asking to wait; try again in a moment")
    }
}
