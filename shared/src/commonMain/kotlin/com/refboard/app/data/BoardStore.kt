package com.refboard.app.data

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
import kotlin.uuid.Uuid

/** Boards are saved as links in a small JSON file in app storage. */
class BoardStore(dir: Path, scope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val file = dir / "boards.json"
    private val listSerializer = ListSerializer(Board.serializer())

    private val _boards = MutableStateFlow(load())
    val boards: StateFlow<List<Board>> = _boards

    init {
        // One writer, always saving the newest list. A StateFlow skips
        // states that are superseded while a write is running, so writes
        // can never land out of order.
        scope.launch(Dispatchers.IO) {
            _boards.drop(1).collect { write(it) }
        }
    }

    private fun load(): List<Board> = try {
        Files.readText(file)?.let { json.decodeFromString(listSerializer, it) } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun write(list: List<Board>) {
        runCatching { Files.writeTextAtomic(file, json.encodeToString(listSerializer, list)) }
    }

    private fun change(transform: (List<Board>) -> List<Board>) = _boards.update(transform)

    fun get(id: String): Board? = _boards.value.firstOrNull { it.id == id }

    fun create(name: String): Board {
        val board = Board(id = Uuid.random().toString(), name = name.trim().ifEmpty { "Untitled" })
        change { listOf(board) + it }
        return board
    }

    /** Adds pins, skipping images already on the board. Returns how many were new. */
    fun addPins(boardId: String, pins: List<Pin>): Int {
        var added = 0
        change { list ->
            list.map { b ->
                if (b.id != boardId) b else {
                    val existing = b.pins.mapTo(HashSet()) { it.imageUrl }
                    val fresh = pins.filter { existing.add(it.imageUrl) }
                    added = fresh.size
                    b.copy(pins = fresh + b.pins)
                }
            }
        }
        return added
    }

    fun removePins(boardId: String, imageUrls: Set<String>) = change { list ->
        list.map { b -> if (b.id != boardId) b else b.withoutPins(imageUrls) }
    }

    /** Moves pins to another board in one step. Returns how many were new there. */
    fun movePins(fromId: String, toId: String, imageUrls: Set<String>): Int {
        if (fromId == toId) return 0
        var added = 0
        change { list ->
            val moving = list.firstOrNull { it.id == fromId }?.pins?.filter { it.imageUrl in imageUrls }.orEmpty()
            list.map { b ->
                when (b.id) {
                    fromId -> b.withoutPins(imageUrls)
                    toId -> {
                        val existing = b.pins.mapTo(HashSet()) { it.imageUrl }
                        val fresh = moving.filter { existing.add(it.imageUrl) }
                        added = fresh.size
                        b.copy(pins = fresh + b.pins)
                    }
                    else -> b
                }
            }
        }
        return added
    }

    fun setCover(boardId: String, imageUrl: String?) = change { list ->
        list.map { if (it.id == boardId) it.copy(cover = imageUrl) else it }
    }

    fun rename(boardId: String, name: String) = change { list ->
        list.map { if (it.id == boardId) it.copy(name = name.trim().ifEmpty { it.name }) else it }
    }

    fun delete(boardId: String) = change { list -> list.filterNot { it.id == boardId } }

    private fun Board.withoutPins(imageUrls: Set<String>) = copy(
        pins = pins.filterNot { it.imageUrl in imageUrls },
        cover = cover?.takeUnless { it in imageUrls },
    )

    // ------------------------------------------------------------ backup

    fun exportJson(favorites: List<Favorite>): String = backupJson.encodeToString(
        Backup.serializer(),
        Backup(exportedAt = nowMillis(), boards = _boards.value, favorites = favorites),
    )

    /** What a restore added. */
    data class Restored(val boards: Int, val pins: Int, val favorites: List<Favorite>)

    /**
     * Merges a backup into the current boards: boards with the same id get
     * the missing pins, others are added. Favourites are returned for the
     * caller to merge.
     */
    fun importJson(text: String): Restored {
        val backup = runCatching { backupJson.decodeFromString(Backup.serializer(), text) }
            .recoverCatching { Backup(boards = backupJson.decodeFromString(listSerializer, text)) }
            .getOrElse { throw IllegalArgumentException("Not a RefBoard backup") }
        val incoming = backup.boards
        var newBoards = 0
        var newPins = 0
        change { list ->
            val byId = list.associateByTo(LinkedHashMap()) { it.id }
            for (b in incoming) {
                val current = byId[b.id]
                if (current == null) {
                    byId[b.id] = b
                    newBoards++
                    newPins += b.pins.size
                } else {
                    val existing = current.pins.mapTo(HashSet()) { it.imageUrl }
                    val fresh = b.pins.filter { existing.add(it.imageUrl) }
                    newPins += fresh.size
                    byId[b.id] = current.copy(pins = current.pins + fresh)
                }
            }
            byId.values.toList()
        }
        return Restored(newBoards, newPins, backup.favorites)
    }

    private val backupJson = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Serializable
    private data class Backup(
        val app: String = "RefBoard",
        val version: Int = 2,
        val exportedAt: Long = 0,
        val boards: List<Board> = emptyList(),
        val favorites: List<Favorite> = emptyList(),
    )
}
