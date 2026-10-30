package com.refboard.app.data

import com.refboard.app.RefBoardApp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.random.Random

/** Hands out a feed one page at a time. */
interface FeedLoader {
    suspend fun next(count: Int): FeedPage
    fun close() {}
}

/** Category, tag, search and listing pages read straight from the site (with thumbnails). */
class SiteLoader(private val url: String, private var firstPage: ListingPage? = null) : FeedLoader {
    private var offset = 0
    private var done = false

    override suspend fun next(count: Int): FeedPage {
        if (done) return FeedPage(emptyList(), done = true)
        val page = firstPage?.also { firstPage = null } ?: SiteApi.listingPage(url, offset)
        offset = page.next
        done = page.done
        return FeedPage(page.items, page.done)
    }
}

/**
 * A search, done the way the site's search box does it. If the words match
 * a tag, category or channel, the site opens that page and so do we. In
 * Gay/Trans mode, other searches get the orientation's word added, since
 * the site's search can't filter by orientation.
 */
class SearchLoader(private val query: String, private val orientationWord: String?) : FeedLoader {
    private var inner: FeedLoader? = null

    override suspend fun next(count: Int): FeedPage {
        inner?.let { return it.next(count) }
        val start = SiteApi.startSearch(query)
        val addWord = !start.redirected && orientationWord != null && !query.contains(orientationWord, ignoreCase = true)
        val loader = if (addWord) {
            SiteLoader(Bridge.searchUrl("$orientationWord $query"))
        } else {
            SiteLoader(start.url, firstPage = start.page)
        }
        inner = loader
        return loader.next(count)
    }
}

/** Any other URL, through gallery-dl. */
class GalleryDlLoader(private val url: String) : FeedLoader {
    private var feedId: Int? = null

    override suspend fun next(count: Int): FeedPage {
        val id = feedId ?: Bridge.startFeed(url).also { feedId = it }
        return Bridge.nextPage(id, count)
    }

    override fun close() {
        feedId?.let { Bridge.closeFeed(it) }
    }
}

/**
 * Takes a page from each part at once and interleaves them. A part that
 * fails is dropped; the mix only fails if every part does.
 */
class MixLoader(parts: List<FeedLoader>) : FeedLoader {
    private val active = parts.toMutableList()

    override suspend fun next(count: Int): FeedPage {
        if (active.isEmpty()) return FeedPage(emptyList(), done = true)
        val perPart = (count / active.size).coerceAtLeast(6)
        val results = coroutineScope {
            active.map { part -> async { part to runCatching { part.next(perPart) } } }.awaitAll()
        }
        val pages = mutableListOf<List<FeedItem>>()
        var firstError: Throwable? = null
        for ((part, result) in results) {
            result.onSuccess { page ->
                pages += page.items
                if (page.done) active.remove(part)
            }.onFailure {
                firstError = firstError ?: it
                active.remove(part)
            }
        }
        if (pages.isEmpty() && firstError != null) throw firstError!!
        val mixed = buildList {
            val longest = pages.maxOfOrNull { it.size } ?: 0
            for (i in 0 until longest) pages.forEach { p -> p.getOrNull(i)?.let(::add) }
        }
        return FeedPage(mixed, done = active.isEmpty())
    }

    override fun close() = active.forEach { it.close() }
}

object Feeds {
    private val listingPath = Regex("""^${Regex.escape(Net.ROOT)}/(popular|recent|rating|likes|views|comments)/?$""")

    fun loader(source: FeedSource): FeedLoader = when (source) {
        is FeedSource.Mix -> MixLoader(source.parts.map { loader(it) })
        is FeedSource.Url ->
            if (listingPath.matches(source.url)) SiteLoader(source.url) else GalleryDlLoader(source.url)
        is FeedSource.Search ->
            SearchLoader(source.query, RefBoardApp.prefs.orientation.value.searchWord)
        is FeedSource.Tag, is FeedSource.Category, is FeedSource.Listing ->
            SiteLoader(Bridge.urlFor(source))
    }

    /**
     * "For you": the tags you pin most and your recent searches, topped up
     * with a few site categories. [seed] changes the mix on refresh.
     */
    fun forYou(
        boards: List<Board>,
        recent: List<String>,
        categories: List<Category>,
        orientation: Orientation,
        seed: Long,
    ): FeedSource {
        val byName = categories.associateBy { it.name.lowercase() }
        val parts = LinkedHashMap<String, FeedSource>()
        // Gay and trans have a main gallery feed of their own; always include it.
        if (orientation != Orientation.Straight) {
            parts[orientation.url()] = FeedSource.Category(orientation.label, orientation.url())
        }
        boards.flatMap { it.pins }.flatMap { it.tags }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(4)
            .forEach { (tag, _) ->
                val cat = byName[tag.lowercase()]
                if (cat != null) parts[cat.url] = FeedSource.Category(cat.name, cat.url)
                else parts["tag:${tag.lowercase()}"] = FeedSource.Tag(tag)
            }
        recent.take(2).forEach { parts["q:${it.lowercase()}"] = FeedSource.Search(it) }
        val random = Random(seed)
        categories.shuffled(random).take((4 - parts.size).coerceAtLeast(1)).forEach {
            parts.putIfAbsent(it.url, FeedSource.Category(it.name, it.url))
        }
        if (parts.isEmpty()) return FeedSource.Listing("popular", "Popular", orientation)
        return FeedSource.Mix("For you", parts.values.shuffled(random))
    }
}
