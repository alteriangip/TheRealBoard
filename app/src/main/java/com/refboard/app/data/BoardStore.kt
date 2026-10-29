package com.refboard.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Boards are saved as links in a small JSON file in app storage. */
class BoardStore(context: Context, private val scope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val file = File(context.filesDir, "boards.json")
    private val writeLock = Any()

    private val _boards = MutableStateFlow(load())
    val boards: StateFlow<List<Board>> = _boards

    private fun load(): List<Board> = try {
        if (file.exists()) json.decodeFromString(ListSerializer(Board.serializer()), file.readText())
        else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun persist(list: List<Board>) {
        scope.launch(Dispatchers.IO) {
            synchronized(writeLock) {
                val tmp = File(file.parentFile, "boards.json.tmp")
                tmp.writeText(json.encodeToString(ListSerializer(Board.serializer()), list))
                tmp.renameTo(file)
            }
        }
    }

    private fun change(transform: (List<Board>) -> List<Board>) {
        _boards.update(transform)
        persist(_boards.value)
    }

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
        list.map { b -> if (b.id != boardId) b else b.copy(pins = b.pins.filterNot { it.imageUrl in imageUrls }) }
    }

    fun rename(boardId: String, name: String) = change { list ->
        list.map { if (it.id == boardId) it.copy(name = name.trim().ifEmpty { it.name }) else it }
    }

    fun delete(boardId: String) = change { list -> list.filterNot { it.id == boardId } }
}
