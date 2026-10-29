package com.refboard.app.data

import kotlinx.serialization.Serializable

@Serializable
data class FeedItem(
    val type: String = "gallery",
    val url: String,
    val thumb: String? = null,
    val title: String = "",
    val tags: List<String> = emptyList(),
)

@Serializable
data class FeedPage(
    val items: List<FeedItem> = emptyList(),
    val done: Boolean = false,
)

@Serializable
data class GalleryImage(
    val url: String,
    val num: Int? = null,
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
    val addedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class Board(
    val id: String,
    val name: String,
    val pins: List<Pin> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
)

/** One image to show in the full-screen viewer. */
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
