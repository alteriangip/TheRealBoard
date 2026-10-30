package com.refboard.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.refboard.app.RefBoardApp
import com.refboard.app.data.Board
import com.refboard.app.data.Bridge
import com.refboard.app.data.Catalog
import com.refboard.app.data.FeedItem
import com.refboard.app.data.FeedLoader
import com.refboard.app.data.FeedPage
import com.refboard.app.data.FeedSource
import com.refboard.app.data.Feeds
import com.refboard.app.data.Pin
import com.refboard.app.data.Thumbs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A scrolling grid of galleries or images from any [FeedSource]. */
class FeedViewModel : ViewModel() {

    var source by mutableStateOf<FeedSource?>(null)
        private set
    val items = mutableStateListOf<FeedItem>()
    private val seen = HashSet<String>()
    /** Goes up each time the grid's contents are replaced, so the grid jumps back to the top. */
    var resetCount by mutableIntStateOf(0)
        private set
    var loading by mutableStateOf(false)
        private set
    var done by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Covers fetched for tiles that arrived without a thumbnail. */
    val covers = mutableStateMapOf<String, String>()
    private val coverRequested = HashSet<String>()

    private var loader: FeedLoader? = null
    private var job: Job? = null
    private var generation = 0

    fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        RefBoardApp.prefs.remember(q)
        open(FeedSource.Search(q))
    }

    /** Opens a tag, as its category page when the site has one by that name. */
    fun openTag(tag: String) {
        val cat = Catalog.categoryNamed(tag, RefBoardApp.prefs.orientation.value)
        open(if (cat != null) FeedSource.Category(cat.name, cat.url) else FeedSource.Tag(tag))
    }

    fun openUrl(url: String) = open(FeedSource.Url(url))

    /** Opens [src] unless it is already showing. */
    fun show(src: FeedSource) {
        if (source != src) open(src)
    }

    /** "For you", rebuilt with a new mix each time unless one is already showing. */
    fun openForYou(fresh: Boolean = false) {
        if (!fresh && source is FeedSource.Mix) return
        val gen = ++generation
        loading = true
        viewModelScope.launch {
            val orientation = RefBoardApp.prefs.orientation.value
            val categories = runCatching { Catalog.loadCategories(orientation) }.getOrDefault(emptyList())
            if (gen != generation) return@launch
            open(
                Feeds.forYou(
                    boards = RefBoardApp.boards.boards.value,
                    recent = RefBoardApp.prefs.recent.value,
                    categories = categories,
                    orientation = orientation,
                    seed = System.currentTimeMillis(),
                ),
            )
        }
    }

    /** Re-shows the current feed for a new content setting. */
    fun orientationChanged(o: com.refboard.app.data.Orientation) {
        when (val s = source) {
            is FeedSource.Listing -> open(s.copy(orientation = o))
            is FeedSource.Mix, null -> openForYou(fresh = true)
            is FeedSource.Search -> open(s)
            else -> Unit
        }
    }

    fun retry() {
        source?.let { open(it) }
    }

    fun clear() {
        reset()
        source = null
    }

    private fun reset() {
        generation++
        resetCount++
        job?.cancel()
        loader?.close()
        loader = null
        items.clear()
        seen.clear()
        emptyPages = 0
        done = false
        error = null
        loading = false
    }

    fun open(src: FeedSource) {
        reset()
        source = src
        val gen = generation
        loading = true
        job = viewModelScope.launch {
            val result = runCatching {
                val l = Feeds.loader(src)
                if (gen != generation) {
                    l.close()
                    throw CancellationException()
                }
                loader = l
                l.next(PAGE)
            }
            if (gen != generation) return@launch
            loading = false
            result.onSuccess { page ->
                add(page)
                // A tag with no page of its own: try it as a plain search.
                if (page.items.isEmpty() && page.done && src is FeedSource.Tag) {
                    open(FeedSource.Search(src.tag))
                }
            }.onFailure { e ->
                if (e is CancellationException) return@onFailure
                if (src is FeedSource.Tag) open(FeedSource.Search(src.tag))
                else error = e.message ?: "Something went wrong"
            }
        }
    }

    fun loadMore() {
        val l = loader ?: return
        if (loading || done || error != null) return
        val gen = generation
        loading = true
        job = viewModelScope.launch {
            val result = runCatching { l.next(PAGE) }
            if (gen != generation) return@launch
            loading = false
            result.onSuccess { add(it) }.onFailure { e ->
                if (e !is CancellationException) error = e.message ?: "Couldn't load more"
            }
        }
    }

    private var emptyPages = 0

    private fun add(page: FeedPage) {
        val fresh = page.items.filter { seen.add(it.url) }
        items.addAll(fresh)
        done = page.done
        // A page of only repeats adds nothing to scroll into, so the grid
        // would never ask for more: fetch the next one ourselves (a few times).
        if (fresh.isEmpty() && !page.done && emptyPages < 3) {
            emptyPages++
            viewModelScope.launch { loadMore() }
        } else if (fresh.isNotEmpty()) {
            emptyPages = 0
        }
        // Only the start of the page: the grid fetches the rest as it scrolls,
        // and every extra request counts against the site's limit.
        prefetch(fresh.take(8).mapNotNull { it.thumb?.let(Thumbs::small) })
    }

    fun resolveCover(galleryUrl: String) {
        if (!coverRequested.add(galleryUrl)) return
        viewModelScope.launch {
            runCatching { Bridge.cover(galleryUrl) }.getOrNull()?.let { covers[galleryUrl] = it }
        }
    }

    fun saveGallery(galleryUrl: String, board: Board) {
        viewModelScope.launch { saveGalleryToBoard(galleryUrl, board) }
    }

    override fun onCleared() {
        loader?.close()
    }

    private companion object {
        const val PAGE = 20
    }
}

/**
 * Downloads thumbnails into the disk cache before their tiles scroll into
 * view, so they appear at once. Kept out of the memory cache, which the
 * grid fills with correctly sized copies itself.
 */
fun prefetch(urls: List<String>) {
    // A board download is using the site's patience; don't add to it.
    if (RefBoardApp.downloader.active) return
    val app = RefBoardApp.instance
    val loader = coil.Coil.imageLoader(app)
    urls.forEach { url ->
        loader.enqueue(
            coil.request.ImageRequest.Builder(app)
                .data(url)
                .memoryCachePolicy(coil.request.CachePolicy.DISABLED)
                // The file is what's wanted; decode it tiny to save the work.
                .size(64)
                .build(),
        )
    }
}

/** Shared by the feed and gallery screens. */
suspend fun saveGalleryToBoard(galleryUrl: String, board: Board) {
    Session.toast("Fetching gallery…")
    runCatching { Bridge.gallery(galleryUrl) }
        .onSuccess { data ->
            val pins = data.images.map { Pin(it.url, galleryUrl, data.title, data.tags, ratio = it.ratio) }
            val added = RefBoardApp.boards.addPins(board.id, pins)
            Session.toast("Added $added to ${board.name}")
        }
        .onFailure { Session.toast("Couldn't save: ${it.message}") }
}
