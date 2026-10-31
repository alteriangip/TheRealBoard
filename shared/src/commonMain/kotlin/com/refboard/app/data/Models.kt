package com.refboard.app.data

import com.refboard.app.platform.nowMillis
import kotlinx.serialization.Serializable

@Serializable
data class FeedItem(
    val type: String = "gallery",
    val url: String,
    val thumb: String? = null,
    val title: String = "",
    val tags: List<String> = emptyList(),
    /** Thumbnail width / height when the site says, so tiles hold their shape while loading. */
    val ratio: Float? = null,
)

@Serializable
data class FeedPage(
    val items: List<FeedItem> = emptyList(),
    val done: Boolean = false,
)

/** One page from a site listing; pass [next] back as the offset. */
@Serializable
data class ListingPage(
    val items: List<FeedItem> = emptyList(),
    val done: Boolean = false,
    val next: Int = 0,
)

/** A site category with its sample image. */
@Serializable
data class Category(
    val name: String,
    val slug: String,
    val url: String,
    val thumb: String? = null,
)

/** An entry in the site's A-Z tag list. */
@Serializable
data class TagEntry(
    val name: String,
    val slug: String,
) {
    val url get() = "${Net.ROOT}/tags/$slug/"
}

@Serializable
data class GalleryImage(
    val url: String,
    val num: Int? = null,
    val thumb: String? = null,
    val ratio: Float? = null,
)

@Serializable
data class GalleryData(
    val url: String,
    val title: String = "",
    val tags: List<String> = emptyList(),
    /** "tags", "categories", "models", "channel" -> values */
    val groups: Map<String, List<String>> = emptyMap(),
    val images: List<GalleryImage> = emptyList(),
)

@Serializable
data class Pin(
    val imageUrl: String,
    val galleryUrl: String? = null,
    val title: String = "",
    val tags: List<String> = emptyList(),
    val addedAt: Long = nowMillis(),
    val ratio: Float? = null,
)

@Serializable
data class Board(
    val id: String,
    val name: String,
    val pins: List<Pin> = emptyList(),
    val createdAt: Long = nowMillis(),
    /** Image chosen as the board's cover; falls back to the newest pins. */
    val cover: String? = null,
)

/** One image to show in the full-screen viewer. */
@Serializable
data class ViewerImage(
    val url: String,
    val galleryUrl: String?,
    val title: String,
    val tags: List<String>,
)

/** What a feed is currently showing. */
sealed interface FeedSource {
    val label: String

    data class Search(val query: String) : FeedSource {
        override val label get() = query
    }

    data class Tag(val tag: String) : FeedSource {
        override val label get() = "#$tag"
    }

    data class Url(val url: String) : FeedSource {
        override val label get() = url.removePrefix("https://").removePrefix("www.")
    }

    data class Category(val name: String, val url: String) : FeedSource {
        override val label get() = name
    }

    /** One of the site's single-page listings: popular, recent, rating… */
    data class Listing(
        val kind: String,
        val title: String,
        val orientation: Orientation = Orientation.Straight,
    ) : FeedSource {
        override val label get() = title
    }

    /** Several feeds interleaved, e.g. "For you". */
    data class Mix(val title: String, val parts: List<FeedSource>) : FeedSource {
        override val label get() = title
    }
}

object Thumbs {
    private val sizeSegment = Regex("""(cdni\.pornpics\.com)/(\d{3,4})/""")

    /**
     * Gallery links point at full-size images. The CDN appears to serve
     * smaller copies under a different size folder; the UI falls back to
     * the full image if the smaller one fails to load.
     */
    fun small(url: String): String =
        sizeSegment.replace(url) { m -> "${m.groupValues[1]}/460/" }
}

/**
 * Which part of the site to browse. The site keeps separate categories,
 * tags and listings for each, under a path prefix.
 */
enum class Orientation(
    val label: String,
    /** Path prefix on the site ("" for straight). */
    val path: String,
    /** The site's own code for its category menu. */
    val menuCode: Int,
    /** Added to searches, since the site's search has no orientation filter. */
    val searchWord: String?,
) {
    Straight("Straight", "", 3, null),
    Gay("Gay", "gay", 1, "gay"),
    Trans("Trans", "shemale", 2, "shemale");

    /** A site page for this orientation, e.g. url("popular/"). */
    fun url(page: String = ""): String =
        SiteParser.ROOT + "/" + (if (path.isEmpty()) "" else "$path/") + page
}
