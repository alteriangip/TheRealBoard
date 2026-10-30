package com.refboard.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The site's categories and tags, kept on disk for a few days so the
 * Categories tab opens instantly. Tag sample images are fetched as tags
 * scroll into view and remembered.
 */
object Catalog {
    private const val MAX_AGE = 3L * 24 * 60 * 60 * 1000
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var dir: File

    /** Lists per orientation; a screen shows the one for the current setting. */
    private val _categories = MutableStateFlow<Map<Orientation, List<Category>>>(emptyMap())
    val categories: StateFlow<Map<Orientation, List<Category>>> = _categories
    private val _tags = MutableStateFlow<Map<Orientation, List<TagEntry>>>(emptyMap())
    val tags: StateFlow<Map<Orientation, List<TagEntry>>> = _tags
    /** Page link (tag or category) -> sample image. */
    private val _samples = MutableStateFlow<Map<String, String>>(emptyMap())
    val samples: StateFlow<Map<String, String>> = _samples

    private val categoriesLock = Mutex()
    private val tagsLock = Mutex()
    private val samplesFile get() = File(dir, "samples.json")
    private val sampleMap = MapSerializer(String.serializer(), String.serializer())

    fun start(context: Context, scope: CoroutineScope) {
        dir = File(context.filesDir, "catalog").apply { mkdirs() }
        scope.launch(Dispatchers.IO) {
            runCatching { json.decodeFromString(sampleMap, samplesFile.readText()) }
                .getOrNull()?.let { saved -> _samples.update { saved + it } }
            // Save at most every few seconds while tiles are scrolling by.
            _samples.drop(1).collect {
                runCatching { writeAtomic(samplesFile, json.encodeToString(sampleMap, it)) }
                delay(3000)
            }
        }
    }

    suspend fun loadCategories(o: Orientation, force: Boolean = false): List<Category> = categoriesLock.withLock {
        load(force, _categories, o, "categories-${o.name}.json", Category.serializer()) { SiteApi.categories(o) }
    }

    suspend fun loadTags(o: Orientation, force: Boolean = false): List<TagEntry> = tagsLock.withLock {
        load(force, _tags, o, "tags-${o.name}.json", TagEntry.serializer()) { SiteApi.tagsIndex(o) }
    }

    /** Fetches a sample image for a tag or category page unless it is known. Cancel it when the tile scrolls away. */
    suspend fun loadSample(pageUrl: String) {
        if (pageUrl in _samples.value) return
        val url = runCatching { SiteApi.tagSample(pageUrl) }.getOrNull() ?: return
        _samples.update { it + (pageUrl to url) }
    }

    fun categoryNamed(name: String, o: Orientation): Category? =
        _categories.value[o]?.firstOrNull { it.name.equals(name, ignoreCase = true) }

    @Serializable
    private data class Saved<T>(val savedAt: Long, val items: List<T>)

    private suspend fun <T> load(
        force: Boolean,
        state: MutableStateFlow<Map<Orientation, List<T>>>,
        o: Orientation,
        fileName: String,
        item: KSerializer<T>,
        fetch: suspend () -> List<T>,
    ): List<T> {
        state.value[o]?.takeIf { !force && it.isNotEmpty() }?.let { return it }
        val file = File(dir, fileName)
        val savedSerializer = Saved.serializer(item)
        val saved = withContext(Dispatchers.IO) {
            runCatching { json.decodeFromString(savedSerializer, file.readText()) }.getOrNull()
        }
        val fresh = saved != null && System.currentTimeMillis() - saved.savedAt < MAX_AGE
        if (saved != null && fresh && !force) {
            state.update { it + (o to saved.items) }
            return saved.items
        }
        return try {
            val items = fetch()
            if (items.isNotEmpty()) {
                state.update { it + (o to items) }
                withContext(Dispatchers.IO) {
                    runCatching {
                        writeAtomic(file, json.encodeToString(savedSerializer, Saved(System.currentTimeMillis(), items)))
                    }
                }
            }
            items
        } catch (e: Exception) {
            // Offline or the site changed: fall back to the last copy.
            if (saved != null) {
                state.update { it + (o to saved.items) }
                saved.items
            } else throw e
        }
    }

    private fun writeAtomic(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(file)
    }
}
