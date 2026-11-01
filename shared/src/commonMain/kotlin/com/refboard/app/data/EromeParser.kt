package com.refboard.app.data

/**
 * Reads erome.com pages. Plain Kotlin with no Android or network code, so
 * it can be tested on its own against saved pages.
 */
object EromeParser {
    const val ROOT = "https://www.erome.com"
    /** Album lists (explore, search, user pages) show this many per page. */
    const val PAGE_SIZE = 36

    data class Album(
        val url: String,
        val thumb: String?,
        val title: String,
        val user: String,
        val images: Int,
        val videos: Int,
    )

    data class Media(
        /** The picture (for a video: its poster frame). */
        val url: String,
        val ratio: Float?,
        /** Set for videos: the .mp4 to play. */
        val video: String?,
    )

    data class AlbumPage(
        val title: String,
        val user: String,
        val tags: List<String>,
        val media: List<Media>,
    )

    private val albumBlock = Regex("""<div class="[^"]*\balbum\b[^"]*" id="album-\d+"""")
    private val albumLink = Regex("""href="(https://www\.erome\.com/a/\w+)"""")
    private val thumbImg = Regex("""class="album-thumbnail[^"]*"[^>]*\ssrc="([^"]+)"|\ssrc="([^"]+)"[^>]*class="album-thumbnail""")
    private val titleRe = Regex("""class="album-title"[^>]*>([^<]*)<""")
    private val userRe = Regex("""class="album-user"[^>]*>([^<]*)<""")
    private val imagesRe = Regex("""class="album-images"[^>]*>(?:\s*<svg.*?</svg>)?\s*(\d+)""", RegexOption.DOT_MATCHES_ALL)
    private val videosRe = Regex("""class="album-videos"[^>]*>(?:\s*<svg.*?</svg>)?\s*(\d+)""", RegexOption.DOT_MATCHES_ALL)

    private val ogTitle = Regex("""property="og:title" content="([^"]*)"""")
    private val profileUser = Regex("""<div class="user-profile.*?href="https://www\.erome\.com/([^"/?#]+)"""", RegexOption.DOT_MATCHES_ALL)
    private val imgFront = Regex("""<img[^>]*class="img-front[^"]*"[^>]*>""")
    private val anyImg = Regex("""<img[^>]*>""")
    private val srcAttr = Regex("""\s(?:data-src|src)="([^"]+)"""")
    private val dataSrcAttr = Regex("""\sdata-src="([^"]+)"""")
    private val widthAttr = Regex("""\swidth="(\d+)"""")
    private val heightAttr = Regex("""\sheight="(\d+)"""")
    private val sourceSrc = Regex("""<source src="([^"]+)"""")
    private val poster = Regex("""\sposter="([^"]+)"""")
    private val tagLink = Regex("""\?q=([^"&]+)"""")

    /** Album tiles on explore, search and user pages. */
    fun albums(html: String): List<Album> {
        val starts = albumBlock.findAll(html).map { it.range.first }.toList()
        val seen = HashSet<String>()
        return starts.mapIndexedNotNull { i, start ->
            val end = if (i + 1 < starts.size) starts[i + 1] else minOf(html.length, start + 4000)
            val block = html.substring(start, end)
            val url = albumLink.find(block)?.groupValues?.get(1) ?: return@mapIndexedNotNull null
            if (!seen.add(url)) return@mapIndexedNotNull null
            val thumb = thumbImg.find(block)?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }
            Album(
                url = url,
                thumb = thumb?.takeIf { it.startsWith("http") },
                title = SiteParser.unescape(titleRe.find(block)?.groupValues?.get(1)?.trim().orEmpty()),
                user = SiteParser.unescape(userRe.find(block)?.groupValues?.get(1)?.trim().orEmpty()),
                images = imagesRe.find(block)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                videos = videosRe.find(block)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            )
        }
    }

    /** An album page: its title, uploader, tags and every photo and video. */
    fun album(html: String): AlbumPage {
        val title = SiteParser.unescape(ogTitle.find(html)?.groupValues?.get(1).orEmpty()).trim()
        val user = profileUser.find(html)?.groupValues?.get(1)?.let { SiteParser.decodeComponent(it) }.orEmpty()
        val tagStart = html.indexOf("<p class=\"mt-10\"")
        val tags = if (tagStart < 0) emptyList() else {
            val end = html.indexOf("</p>", tagStart).let { if (it < 0) html.length else it }
            tagLink.findAll(html.substring(tagStart, end))
                .map { SiteParser.decodeComponent(it.groupValues[1]).trim() }
                .filter { it.isNotEmpty() }.distinct().toList()
        }
        val media = ArrayList<Media>()
        val seen = HashSet<String>()
        for (group in html.split("<div class=\"media-group\"").drop(1)) {
            val video = sourceSrc.find(group)?.groupValues?.get(1)
            if (video != null) {
                val still = poster.find(group)?.groupValues?.get(1) ?: continue
                if (seen.add(still)) media += Media(still, 16f / 9f, video)
                continue
            }
            // The page shows each photo twice (a blurred backdrop and the photo); take the front one.
            val tag = imgFront.find(group)?.value ?: anyImg.find(group)?.value ?: continue
            val src = (dataSrcAttr.find(tag) ?: srcAttr.find(tag))?.groupValues?.get(1) ?: continue
            if (!src.startsWith("http") || !seen.add(src)) continue
            val w = widthAttr.find(tag)?.groupValues?.get(1)?.toFloatOrNull()
            val h = heightAttr.find(tag)?.groupValues?.get(1)?.toFloatOrNull()
            media += Media(src, if (w != null && h != null) SiteParser.ratio(w, h) else null, null)
        }
        return AlbumPage(title, user, tags, media)
    }

    // ------------------------------------------------------------ urls

    fun searchUrl(query: String): String = "$ROOT/search?q=" + SiteParser.encodeComponent(query.trim())

    fun userUrl(user: String): String = "$ROOT/" + SiteParser.encodeComponent(user.trim()).replace("+", "%20") + "?t=posts"

    fun isAlbum(url: String) = Regex("""^https://(?:www\.)?erome\.com/a/\w+""").containsMatchIn(url)

    /** The same page with ?page=N (or &page=N) set. */
    fun pageUrl(url: String, page: Int): String {
        val base = url.replace(Regex("""[?&]page=\d+"""), "")
        if (page <= 1) return base
        return base + (if ('?' in base) "&" else "?") + "page=$page"
    }

    /** Photos have a small copy in a "thumbs" folder next to them. */
    fun thumbOf(url: String): String {
        if (!Regex("""^https://s\d+\.erome\.com/""").containsMatchIn(url) || "/thumbs/" in url) return url
        val path = url.substringBefore('?')
        val cut = path.lastIndexOf('/')
        return path.substring(0, cut) + "/thumbs" + path.substring(cut)
    }
}
