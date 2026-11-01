package com.refboard.app.data

import com.refboard.app.Refboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
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
import com.refboard.app.platform.Files
import com.refboard.app.platform.fileSystem
import com.refboard.app.platform.nowMillis
import okio.Path

/**
 * The site's categories and tags, kept on disk for a few days so the
 * Categories tab opens instantly. Tag sample images are fetched as tags
 * scroll into view and remembered.
 */
object Catalog {
    private const val MAX_AGE = 3L * 24 * 60 * 60 * 1000
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var dir: Path

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
    private val samplesFile get() = dir / "samples.json"
    private val sampleMap = MapSerializer(String.serializer(), String.serializer())

    fun start(dataDir: Path, scope: CoroutineScope) {
        dir = dataDir / "catalog"
        runCatching { fileSystem.createDirectories(dir) }
        scope.launch(Dispatchers.IO) {
            runCatching { json.decodeFromString(sampleMap, Files.readText(samplesFile)!!) }
                .getOrNull()?.let { saved -> _samples.update { saved + it } }
            // Save at most every few seconds while tiles are scrolling by.
            _samples.drop(1).collect {
                runCatching { Files.writeTextAtomic(samplesFile, json.encodeToString(sampleMap, it)) }
                delay(3000)
            }
        }
    }

    suspend fun loadCategories(o: Orientation, force: Boolean = false): List<Category> = categoriesLock.withLock {
        load(force, _categories, o, "categories-${o.name}.json", Category.serializer()) { Refboard.site.categories(o) }
    }

    suspend fun loadTags(o: Orientation, force: Boolean = false): List<TagEntry> = tagsLock.withLock {
        load(force, _tags, o, "tags-${o.name}.json", TagEntry.serializer()) { Refboard.site.tagsIndex(o) }
    }

    /** Fetches a sample image for a tag or category page unless it is known. Cancel it when the tile scrolls away. */
    suspend fun loadSample(pageUrl: String) {
        if (pageUrl in _samples.value) return
        val url = runCatching { Refboard.site.sample(pageUrl) }.getOrNull() ?: return
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
        val file = dir / fileName
        val savedSerializer = Saved.serializer(item)
        val saved = withContext(Dispatchers.IO) {
            runCatching { json.decodeFromString(savedSerializer, Files.readText(file)!!) }.getOrNull()
        }
        val fresh = saved != null && nowMillis() - saved.savedAt < MAX_AGE
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
                        Files.writeTextAtomic(file, json.encodeToString(savedSerializer, Saved(nowMillis(), items)))
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
}
