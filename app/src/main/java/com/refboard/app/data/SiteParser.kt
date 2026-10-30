package com.refboard.app.data

/**
 * Reads the site's HTML pages. Plain Kotlin with no Android or network
 * code, so it can be tested on its own against saved pages.
 */
object SiteParser {
    const val ROOT = "https://www.pornpics.com"

    /** A thumbnail tile: a link, its image, a name and the image's width/height. */
    data class Tile(val href: String, val thumb: String?, val title: String, val ratio: Float?)

    data class Image(val url: String, val thumb: String?, val ratio: Float?)

    data class Gallery(
        val title: String,
        val groups: Map<String, List<String>>,
        val images: List<Image>,
    )

    // <li … style='aspect-ratio: 300/450;'><a class='rel-link' href='…' …>…</a>
    private val tileRe = Regex(
        """<li([^>]*)>\s*<a\s+class=['"]rel-link['"]\s+href=['"]([^'"]+)['"]([^>]*)>(.*?)</a>""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val aspectRe = Regex("""aspect-ratio:\s*(\d+)\s*/\s*(\d+)""")
    private val sizeRe = Regex("""data-size=['"](\d+)x(\d+)['"]""")
    private val dataSrcRe = Regex("""data-src=['"]([^'"]+)['"]""")
    private val srcRe = Regex("""\ssrc=['"]([^'"]+)['"]""")
    private val altRe = Regex("""alt=['"]([^'"]*)['"]""")
    private val h2Re = Regex("""<span class=['"]h2['"]>([^<]+)</span>""")
    private val tagLinkRe = Regex("""<a href=['"]/tags/([^/'"]+)/['"][^>]*>([^<]+)</a>""")
    private val categoryPath = Regex("""/[a-z0-9-]+/""")
    private val htmlTag = Regex("""<[^>]+>""")
    private val entity = Regex("""&(#x[0-9a-fA-F]+|#\d+|[a-zA-Z]+);""")

    fun abs(url: String?): String? = when {
        url.isNullOrEmpty() -> null
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> ROOT + url
        else -> url
    }

    fun tiles(html: String): List<Tile> = tileRe.findAll(html).map { m ->
        val (liAttrs, href, aAttrs, inner) = m.destructured
        val img = (dataSrcRe.find(inner) ?: srcRe.find(inner))?.groupValues?.get(1)
            ?.takeUnless { "1px" in it }
        val name = (h2Re.find(inner) ?: altRe.find(inner))?.groupValues?.get(1)?.trim().orEmpty()
        val ratio = (sizeRe.find(aAttrs) ?: aspectRe.find(liAttrs))?.let { r ->
            ratio(r.groupValues[1].toFloat(), r.groupValues[2].toFloat())
        }
        Tile(abs(href)!!, abs(img), unescape(name), ratio)
    }.toList()

    fun ratio(width: Float, height: Float): Float? =
        if (width > 0f && height > 0f) (width / height).coerceIn(0.2f, 5f) else null

    /** Category tiles on the home page: links like /slug/. */
    fun categories(html: String): List<Tile> {
        val seen = HashSet<String>()
        return tiles(html).filter { t ->
            val path = t.href.removePrefix(ROOT)
            t.href.startsWith(ROOT) && categoryPath.matches(path) && seen.add(path)
        }
    }

    /** (name, slug) for every tag on the A-Z tag page. */
    fun tags(html: String): List<Pair<String, String>> {
        val seen = HashSet<String>()
        return tagLinkRe.findAll(html).mapNotNull { m ->
            val slug = m.groupValues[1]
            if (seen.add(slug)) unescape(m.groupValues[2].trim()) to slug else null
        }.toList()
    }

    /** Gallery tiles on a listing, category, tag or search page. */
    fun galleryTiles(html: String): List<Tile> = tiles(html).filter { "/galleries/" in it.href }

    /** A gallery page: title, tag groups and its images (full size, with thumbnails). */
    fun gallery(html: String): Gallery {
        val c = Cursor(html)
        val title = unescape(c.extract("<h1>", "<")).trim()
        val channel = splitHtml(c.extract(">Channel:&nbsp;", "</div>"))
        val models = splitHtml(c.extract(">Models:", "<span class=\"suggest"))
        val categories = splitHtml(c.extract(">Categories:", "<span class=\"suggest"))
        val tags = splitHtml(c.extract(">Tags:", " </div>"))
        val images = tiles(html)
            .filter { !it.href.contains("/galleries/") && it.href.contains("cdni.") }
            .distinctBy { it.href }
            .map { Image(it.href, it.thumb, it.ratio) }
        val groups = linkedMapOf(
            "models" to models.sorted(),
            "channel" to channel.sorted(),
            "categories" to categories.sorted(),
            "tags" to tags.sorted(),
        ).filterValues { it.isNotEmpty() }
        return Gallery(title, groups, images)
    }

    /** Text pieces between HTML tags, like gallery-dl's text.split_html. */
    fun splitHtml(fragment: String): List<String> =
        fragment.split(htmlTag).map { unescape(it).trim() }.filter { it.isNotEmpty() }

    fun unescape(text: String): String {
        if ('&' !in text) return text
        return entity.replace(text) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                else -> namedEntities[e]
            } ?: m.value
        }
    }

    private val namedEntities = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
    )

    /** Reads a page front to back, like gallery-dl's text.extract_from. */
    private class Cursor(private val s: String) {
        private var pos = 0

        fun extract(begin: String, end: String): String {
            val i = s.indexOf(begin, pos)
            if (i < 0) return ""
            val start = i + begin.length
            val j = s.indexOf(end, start)
            if (j < 0) return ""
            pos = j + end.length
            return s.substring(start, j)
        }
    }

    // ------------------------------------------------------------ urls

    /** Same form the site and gallery-dl use: words joined by "-". */
    fun searchUrl(query: String): String =
        "$ROOT/?q=" + query.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
            .joinToString("-") { java.net.URLEncoder.encode(it, "UTF-8") }

    fun searchQuery(url: String): String? {
        val q = Regex("""^${Regex.escape(ROOT)}/\?q=([^&#]+)""").find(url)?.groupValues?.get(1) ?: return null
        return java.net.URLDecoder.decode(q, "UTF-8").replace("-", " ")
    }

    fun slug(text: String) = text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    fun tagUrl(tag: String) = "$ROOT/tags/${slug(tag)}/"
}
