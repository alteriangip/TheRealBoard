package com.refboard.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Recent searches (newest first) and the chosen content orientation. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("refboard", Context.MODE_PRIVATE)
    private val sep = "\u001F"

    private val _orientation = MutableStateFlow(
        Orientation.entries.firstOrNull { it.name == sp.getString("orientation", null) } ?: Orientation.Straight
    )
    val orientation: StateFlow<Orientation> = _orientation

    fun setOrientation(o: Orientation) {
        _orientation.value = o
        sp.edit().putString("orientation", o.name).apply()
    }

    private val _recent = MutableStateFlow(
        sp.getString("recent", "")!!.split(sep).filter { it.isNotBlank() }
    )
    val recent: StateFlow<List<String>> = _recent

    fun remember(term: String) {
        val t = term.trim()
        if (t.isEmpty()) return
        val list = (listOf(t) + _recent.value.filterNot { it.equals(t, ignoreCase = true) }).take(20)
        _recent.value = list
        sp.edit().putString("recent", list.joinToString(sep)).apply()
    }

    fun forget(term: String) {
        val list = _recent.value.filterNot { it == term }
        _recent.value = list
        sp.edit().putString("recent", list.joinToString(sep)).apply()
    }
}
