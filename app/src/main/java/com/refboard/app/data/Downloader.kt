package com.refboard.app.data

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

data class DownloadState(
    val total: Int,
    val done: Int = 0,
    val failed: Int = 0,
    val finished: Boolean = false,
)

/** Saves images into Pictures/RefBoard/<folder> so they show up in the gallery app. */
class Downloader(private val context: Context, private val scope: CoroutineScope) {

    /** Keyed by board id (or "single" for one-off saves). */
    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states

    fun downloadBoard(board: Board) {
        if (_states.value[board.id]?.finished == false) return
        val urls = board.pins.map { it.imageUrl }
        val folder = safeName(board.name)
        _states.update { it + (board.id to DownloadState(total = urls.size)) }
        scope.launch(Dispatchers.IO) {
            for (url in urls) {
                val ok = runCatching { save(url, folder) }.isSuccess
                _states.update { m ->
                    val s = m[board.id] ?: return@update m
                    m + (board.id to if (ok) s.copy(done = s.done + 1) else s.copy(failed = s.failed + 1))
                }
            }
            _states.update { m -> m[board.id]?.let { m + (board.id to it.copy(finished = true)) } ?: m }
        }
    }

    fun clear(key: String) = _states.update { it - key }

    suspend fun saveOne(url: String, folder: String = "Saved"): Boolean =
        withContext(Dispatchers.IO) { runCatching { save(url, safeName(folder)) }.isSuccess }

    private fun save(url: String, folder: String) {
        val request = Request.Builder().url(url).build()
        Net.client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body ?: error("empty body")
            val name = url.substringBefore('?').substringAfterLast('/').ifEmpty { "image.jpg" }
            val mime = body.contentType()?.toString()?.substringBefore(';') ?: mimeFor(name)

            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RefBoard/$folder")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("could not create file")
            try {
                resolver.openOutputStream(uri)?.use { out -> body.byteStream().copyTo(out) }
                    ?: error("could not open file")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        }
    }

    private fun mimeFor(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    private fun safeName(name: String) =
        name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifEmpty { "Board" }.take(60)
}
