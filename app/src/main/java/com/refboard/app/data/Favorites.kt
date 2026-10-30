package com.refboard.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** A model or channel you follow. */
@Serializable
data class Favorite(
    /** [MODEL] or [CHANNEL]. */
    val kind: String,
    val name: String,
    val addedAt: Long = System.currentTimeMillis(),
    /** Thumbnail of their newest gallery, fetched once and remembered. */
    val cover: String? = null,
) {
    val key get() = key(kind, name)

    /**
     * Their galleries. Like gallery-dl, this searches by name: the site's
     * own model and channel pages can't be paged past the first 20.
     */
    val url get() = SiteParser.searchUrl(name)

    companion object {
        const val MODEL = "model"
        const val CHANNEL = "channel"
        fun key(kind: String, name: String) = "$kind:${name.trim().lowercase()}"
    }
}

/** Favourites saved as a small JSON file, written by a single writer like boards. */
class FavoritesStore(context: Context, scope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true }
    private val file = File(context.filesDir, "favorites.json")
    private val serializer = ListSerializer(Favorite.serializer())

    private val _favorites = MutableStateFlow(load())
    val favorites: StateFlow<List<Favorite>> = _favorites

    init {
        scope.launch(Dispatchers.IO) {
            _favorites.drop(1).collect { list ->
                runCatching {
                    val tmp = File(file.parentFile, "favorites.json.tmp")
                    tmp.writeText(json.encodeToString(serializer, list))
                    tmp.renameTo(file)
                }
            }
        }
    }

    private fun load(): List<Favorite> =
        runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())

    fun isFavorite(kind: String, name: String) = _favorites.value.any { it.key == Favorite.key(kind, name) }

    /** Adds or removes; returns true if it is now a favourite. */
    fun toggle(kind: String, name: String): Boolean {
        val key = Favorite.key(kind, name)
        var added = false
        _favorites.update { list ->
            if (list.any { it.key == key }) list.filterNot { it.key == key }
            else {
                added = true
                listOf(Favorite(kind, name.trim())) + list
            }
        }
        return added
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
