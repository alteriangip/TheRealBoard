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
import com.refboard.app.data.FeedItem
import com.refboard.app.data.FeedSource
import com.refboard.app.data.Pin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ExploreViewModel : ViewModel() {

    var source by mutableStateOf<FeedSource?>(null)
        private set
    val items = mutableStateListOf<FeedItem>()
    var loading by mutableStateOf(false)
        private set
    var done by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Covers fetched for tiles that arrived without a thumbnail. */
    val covers = mutableStateMapOf<String, String>()
    private val coverRequested = HashSet<String>()

    private var feedId: Int? = null
    private var job: Job? = null
    private var generation = 0

    fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        RefBoardApp.prefs.remember(q)
        open(FeedSource.Search(q))
    }

    fun openTag(tag: String) = open(FeedSource.Tag(tag))
    fun openUrl(url: String) = open(FeedSource.Url(url))
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
        feedId?.let { Bridge.closeFeed(it) }
        feedId = null
        items.clear()
        done = false
        error = null
        loading = false
    }

    private fun open(src: FeedSource) {
        reset()
        source = src
        val gen = generation
        loading = true
        job = viewModelScope.launch {
            val result = runCatching {
                val url = withContext(Dispatchers.IO) { Bridge.urlFor(src) }
                val id = Bridge.startFeed(url)
                feedId = id
                Bridge.nextPage(id, 20)
            }
            if (gen != generation) return@launch
            loading = false
            result.onSuccess { page ->
                items.addAll(page.items.filter { new -> items.none { it.url == new.url } })
                done = page.done
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
        val id = feedId ?: return
        if (loading || done || error != null) return
        val gen = generation
        loading = true
        job = viewModelScope.launch {
            val result = runCatching { Bridge.nextPage(id, 20) }
            if (gen != generation) return@launch
            loading = false
            result.onSuccess { page ->
                items.addAll(page.items.filter { new -> items.none { it.url == new.url } })
                done = page.done
            }.onFailure { e ->
                if (e !is CancellationException) error = e.message ?: "Couldn't load more"
            }
        }
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
        feedId?.let { Bridge.closeFeed(it) }
    }
}

/** Shared by the explore and gallery screens. */
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
