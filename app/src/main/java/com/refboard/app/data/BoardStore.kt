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
import java.util.UUID

/** Boards are saved as links in a small JSON file in app storage. */
class BoardStore(context: Context, scope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val file = File(context.filesDir, "boards.json")
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
        if (file.exists()) json.decodeFromString(listSerializer, file.readText())
        else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun write(list: List<Board>) {
        runCatching {
            val tmp = File(file.parentFile, "boards.json.tmp")
            tmp.writeText(json.encodeToString(listSerializer, list))
            tmp.renameTo(file)
        }
    }

    private fun change(transform: (List<Board>) -> List<Board>) = _boards.update(transform)

    fun get(id: String): Board? = _boards.value.firstOrNull { it.id == id }

    fun create(name: String): Board {
        val board = Board(id = UUID.randomUUID().toString(), name = name.trim().ifEmpty { "Untitled" })
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

    fun exportJson(): String = backupJson.encodeToString(
        Backup.serializer(),
        Backup(exportedAt = System.currentTimeMillis(), boards = _boards.value),
    )

    /**
     * Merges a backup into the current boards: boards with the same id get
     * the missing pins, others are added. Returns (boards added, pins added).
     */
    fun importJson(text: String): Pair<Int, Int> {
        val incoming = runCatching { backupJson.decodeFromString(Backup.serializer(), text).boards }
            .recoverCatching { backupJson.decodeFromString(listSerializer, text) }
            .getOrElse { throw IllegalArgumentException("Not a RefBoard backup") }
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
        return newBoards to newPins
    }

    private val backupJson = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Serializable
    private data class Backup(
        val app: String = "RefBoard",
        val version: Int = 1,
        val exportedAt: Long = 0,
        val boards: List<Board> = emptyList(),
    )
}
