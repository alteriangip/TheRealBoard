package com.refboard.app.data

import com.refboard.app.platform.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okio.Path

/**
 * Recent searches (newest first) and the chosen content orientation, kept
 * in a small JSON file. [legacy] carries over settings an older version of
 * the app stored elsewhere, the first time only.
 */
class Prefs(dir: Path, legacy: Map<String, String>) {
    private val file = dir / "prefs.json"
    private val json = Json { ignoreUnknownKeys = true }
    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())
    private val sep = "\u001F"
    private val values: MutableMap<String, String> =
        (Files.readText(file)?.let { runCatching { json.decodeFromString(mapSerializer, it) }.getOrNull() } ?: legacy)
            .toMutableMap()

    private fun put(key: String, value: String) {
        values[key] = value
        runCatching { Files.writeTextAtomic(file, json.encodeToString(mapSerializer, values)) }
    }

    private val _orientation = MutableStateFlow(
        Orientation.entries.firstOrNull { it.name == values["orientation"] } ?: Orientation.Straight
    )
    val orientation: StateFlow<Orientation> = _orientation

    fun setOrientation(o: Orientation) {
        _orientation.value = o
        put("orientation", o.name)
    }

    private val _recent = MutableStateFlow(
        values["recent"].orEmpty().split(sep).filter { it.isNotBlank() }
    )
    val recent: StateFlow<List<String>> = _recent

    fun remember(term: String) {
        val t = term.trim()
        if (t.isEmpty()) return
        val list = (listOf(t) + _recent.value.filterNot { it.equals(t, ignoreCase = true) }).take(20)
        _recent.value = list
        put("recent", list.joinToString(sep))
    }

    fun forget(term: String) {
        val list = _recent.value.filterNot { it == term }
        _recent.value = list
        put("recent", list.joinToString(sep))
    }

    /** For small things like the viewer's last page. */
    fun getInt(key: String, default: Int): Int = values[key]?.toIntOrNull() ?: default
    fun putInt(key: String, value: Int) = put(key, value.toString())
}
