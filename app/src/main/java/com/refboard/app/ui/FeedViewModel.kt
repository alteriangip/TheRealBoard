package com.refboard.app.ui

import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A scrolling grid of galleries or images from any [FeedSource]. */
class FeedViewModel : ViewModel() {

    var source by mutableStateOf<FeedSource?>(null)
        private set
    val items = mutableStateListOf<FeedItem>()
    private val seen = HashSet<String>()
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
        val cat = Catalog.categoryNamed(tag)
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
            val categories = runCatching { Catalog.loadCategories() }.getOrDefault(emptyList())
            if (gen != generation) return@launch
            open(
                Feeds.forYou(
                    boards = RefBoardApp.boards.boards.value,
                    recent = RefBoardApp.prefs.recent.value,
                    categories = categories,
                    seed = System.currentTimeMillis(),
                ),
            )
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
        job?.cancel()
        loader?.close()
        loader = null
        items.clear()
        seen.clear()
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

    private fun add(page: FeedPage) {
        items.addAll(page.items.filter { seen.add(it.url) })
        done = page.done
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

/** Shared by the feed and gallery screens. */
suspend fun saveGalleryToBoard(galleryUrl: String, board: Board) {
    Session.toast("Fetching gallery…")
    runCatching { Bridge.gallery(galleryUrl) }
        .onSuccess { data ->
            val pins = data.images.map { Pin(it.url, galleryUrl, data.title, data.tags) }
            val added = RefBoardApp.boards.addPins(board.id, pins)
            Session.toast("Added $added to ${board.name}")
        }
        .onFailure { Session.toast("Couldn't save: ${it.message}") }
}
