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

    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories
    private val _tags = MutableStateFlow<List<TagEntry>>(emptyList())
    val tags: StateFlow<List<TagEntry>> = _tags
    /** Tag slug -> sample image. */
    private val _samples = MutableStateFlow<Map<String, String>>(emptyMap())
    val samples: StateFlow<Map<String, String>> = _samples

    private val categoriesLock = Mutex()
    private val tagsLock = Mutex()
    private val samplesFile get() = File(dir, "tag_samples.json")
    private val sampleMap = MapSerializer(String.serializer(), String.serializer())

    fun start(context: Context, scope: CoroutineScope) {
        dir = File(context.filesDir, "catalog").apply { mkdirs() }
        scope.launch(Dispatchers.IO) {
            runCatching { json.decodeFromString(sampleMap, samplesFile.readText()) }
                .getOrNull()?.let { saved -> _samples.update { saved + it } }
            // Save at most every few seconds while tags are scrolling by.
            _samples.drop(1).collect {
                runCatching { writeAtomic(samplesFile, json.encodeToString(sampleMap, it)) }
                delay(3000)
            }
        }
    }

    suspend fun loadCategories(force: Boolean = false): List<Category> = categoriesLock.withLock {
        load(force, _categories, "categories.json", Category.serializer()) { SiteApi.categories() }
    }

    suspend fun loadTags(force: Boolean = false): List<TagEntry> = tagsLock.withLock {
        load(force, _tags, "tags.json", TagEntry.serializer()) { SiteApi.tagsIndex() }
    }

    /** Fetches a tag's sample image unless it is known. Cancel it when the tag scrolls away. */
    suspend fun loadSample(tag: TagEntry) {
        if (tag.slug in _samples.value) return
        val url = runCatching { SiteApi.tagSample(tag.url) }.getOrNull() ?: return
        _samples.update { it + (tag.slug to url) }
    }

    fun categoryNamed(name: String): Category? =
        _categories.value.firstOrNull { it.name.equals(name, ignoreCase = true) }

    @Serializable
    private data class Saved<T>(val savedAt: Long, val items: List<T>)

    private suspend fun <T> load(
        force: Boolean,
        state: MutableStateFlow<List<T>>,
        fileName: String,
        item: KSerializer<T>,
        fetch: suspend () -> List<T>,
    ): List<T> {
        if (!force && state.value.isNotEmpty()) return state.value
        val file = File(dir, fileName)
        val savedSerializer = Saved.serializer(item)
        val saved = withContext(Dispatchers.IO) {
            runCatching { json.decodeFromString(savedSerializer, file.readText()) }.getOrNull()
        }
        val fresh = saved != null && System.currentTimeMillis() - saved.savedAt < MAX_AGE
        if (saved != null && fresh && !force) {
            state.value = saved.items
            return saved.items
        }
        return try {
            val items = fetch()
            if (items.isNotEmpty()) {
                state.value = items
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
                state.value = saved.items
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
