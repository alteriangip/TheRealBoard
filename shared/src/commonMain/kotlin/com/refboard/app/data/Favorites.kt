package com.refboard.app.data

import com.refboard.app.Refboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import com.refboard.app.platform.Files
import com.refboard.app.platform.nowMillis
import okio.Path

/**
 * A bookmark to a model, channel, category or tag. Only the name and link
 * are kept; opening it loads the newest galleries from the site.
 */
@Serializable
data class Favorite(
    /** [MODEL], [CHANNEL] or [TOPIC]. */
    val kind: String,
    val name: String,
    val addedAt: Long = nowMillis(),
    /** Thumbnail of the newest gallery, fetched once and remembered. */
    val cover: String? = null,
    /** The page to open; for categories and tags. */
    val link: String? = null,
) {
    val key get() = key(kind, name)

    /**
     * The galleries to show. Models and channels are searched by name like
     * gallery-dl does: the site's own pages for them can't be paged past
     * the first 20. Categories and tags open their own page.
     */
    val url get() = link ?: Refboard.site.searchUrl(name)

    companion object {
        const val MODEL = "model"
        const val CHANNEL = "channel"
        /** Categories and tags, kept together. */
        const val TOPIC = "topic"
        fun key(kind: String, name: String) = "$kind:${name.trim().lowercase()}"
    }
}

/** Favourites saved as a small JSON file, written by a single writer like boards. */
class FavoritesStore(dir: Path, scope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true }
    private val file = dir / "favorites.json"
    private val serializer = ListSerializer(Favorite.serializer())

    private val _favorites = MutableStateFlow(load())
    val favorites: StateFlow<List<Favorite>> = _favorites

    init {
        scope.launch(Dispatchers.IO) {
            _favorites.drop(1).collect { list ->
                runCatching { Files.writeTextAtomic(file, json.encodeToString(serializer, list)) }
            }
        }
    }

    private fun load(): List<Favorite> =
        runCatching { json.decodeFromString(serializer, Files.readText(file)!!) }.getOrDefault(emptyList())

    fun isFavorite(kind: String, name: String) = _favorites.value.any { it.key == Favorite.key(kind, name) }

    /** Adds or removes; returns true if it is now a favourite. */
    fun toggle(kind: String, name: String, link: String? = null): Boolean {
        val key = Favorite.key(kind, name)
        var added = false
        _favorites.update { list ->
            if (list.any { it.key == key }) list.filterNot { it.key == key }
            else {
                added = true
                listOf(Favorite(kind, name.trim(), link = link)) + list
            }
        }
        return added
    }

    /** The favourite that opens [url], if any (whatever its kind). */
    fun forUrl(url: String): Favorite? = _favorites.value.firstOrNull { it.url == url }

    /** Stars or unstars a page by its link; new ones are saved as categories/tags. */
    fun toggleUrl(name: String, url: String): Boolean {
        val existing = forUrl(url)
        if (existing != null) {
            remove(existing)
            return false
        }
        return toggle(Favorite.TOPIC, name, url)
    }

    fun remove(fav: Favorite) = _favorites.update { list -> list.filterNot { it.key == fav.key } }

    fun setCover(fav: Favorite, cover: String) = _favorites.update { list ->
        list.map { if (it.key == fav.key) it.copy(cover = cover) else it }
    }

    /** Adds favourites from a backup that aren't here yet; returns how many. */
    fun merge(incoming: List<Favorite>): Int {
        var added = 0
        _favorites.update { list ->
            val keys = list.mapTo(HashSet()) { it.key }
            val fresh = incoming.filter { keys.add(it.key) }
            added = fresh.size
            list + fresh
        }
        return added
    }
}
