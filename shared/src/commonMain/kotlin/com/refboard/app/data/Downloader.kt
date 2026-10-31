package com.refboard.app.data

import com.refboard.app.Refboard
import com.refboard.app.platform.Platform
import com.refboard.app.platform.fileSystem
import com.refboard.app.platform.nowMillis
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.random.Random

data class DownloadState(
    val total: Int,
    val done: Int = 0,
    /** Already in the folder from an earlier download; counted in [done]. */
    val skipped: Int = 0,
    val failed: Int = 0,
    val finished: Boolean = false,
    val cancelled: Boolean = false,
    /** Copied from images the app had already loaded; counted in [done]. */
    val fromCache: Int = 0,
    /** Set while waiting out the site's rate limit. */
    val pausedUntil: Long? = null,
) {
    val handled get() = done + failed
}

/**
 * Saves pictures where the user can find them (Pictures/RefBoard on
 * Android, the app's folder in Files on iPad). Board downloads run in the
 * background on Android; the device decides how ([Platform.services]).
 */
class Downloader {

    /** Keyed by board id. */
    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states

    private val sink get() = Platform.services.imageSink

    /** Where pictures end up, for messages. */
    val savedLabel: String get() = sink.label

    fun downloadBoard(board: Board) {
        if (_states.value[board.id]?.finished == false) return
        _states.update { it + (board.id to DownloadState(total = board.pins.size)) }
        Platform.services.startBoardDownload(board.id)
    }

    fun cancel(boardId: String) = Platform.services.cancelBoardDownload(boardId)

    fun clear(key: String) = _states.update { it - key }

    suspend fun saveOne(url: String, folder: String = "Saved"): Boolean =
        withContext(Dispatchers.IO) {
            val dir = safeName(folder)
            saveFromCache(url, dir) || runCatching { save(url, dir) }.isSuccess
        }

    /** True while any board download is running; browsing prefetch backs off meanwhile. */
    val active: Boolean get() = _states.value.values.any { !it.finished }

    /**
     * Does the actual board download; called by the device's background job.
     *
     * Images the app already has in its image cache are copied from there
     * with no request at all. The rest are fetched two at a time with a
     * short random pause after each (about 2 images a second, so 2,000
     * take 15-20 minutes) to stay under the site's rate limit. If the site
     * pushes back anyway (429/403/503), every worker waits it out, longer
     * each time, and then retries the same image.
     */
    suspend fun runBoard(boardId: String, onProgress: suspend (DownloadState) -> Unit) {
        val board = Refboard.boards.get(boardId) ?: return
        val urls = board.pins.map { it.imageUrl }.distinct()
        val folder = safeName(board.name)
        _states.update { it + (boardId to DownloadState(total = urls.size)) }

        fun bump(transform: (DownloadState) -> DownloadState): DownloadState? {
            var now: DownloadState? = null
            _states.update { m ->
                val s = m[boardId] ?: return@update m
                transform(s).also { now = it }.let { m + (boardId to it) }
            }
            return now
        }

        val gate = RateGate()
        try {
            withContext(Dispatchers.IO) {
                val existing = runCatching { sink.existing(folder) }.getOrDefault(emptySet())
                val queue = Channel<String>(Channel.UNLIMITED)
                urls.forEach { queue.trySend(it) }
                queue.close()
                coroutineScope {
                    repeat(WORKERS) {
                        launch {
                            for (url in queue) {
                                val state = when {
                                    LocalImages.fileName(url) in existing ->
                                        bump { it.copy(done = it.done + 1, skipped = it.skipped + 1) }
                                    saveFromCache(url, folder) ->
                                        bump { it.copy(done = it.done + 1, fromCache = it.fromCache + 1) }
                                    fetchWithBackoff(url, folder, gate) { until ->
                                        bump { it.copy(pausedUntil = until) }?.let { s -> onProgress(s) }
                                    } -> bump { it.copy(done = it.done + 1, pausedUntil = null) }
                                    else -> bump { it.copy(failed = it.failed + 1) }
                                }
                                state?.let { onProgress(it) }
                            }
                        }
                    }
                }
            }
            bump { it.copy(finished = true, pausedUntil = null) }
        } catch (e: CancellationException) {
            bump { it.copy(finished = true, cancelled = true, pausedUntil = null) }
            throw e
        }
    }

    /** Shared by a download's workers: when the site says slow down, everyone waits. */
    private class RateGate {
        @Volatile var pausedUntil = 0L
        @Volatile var backoffMs = 30_000L

        suspend fun awaitOpen() {
            while (true) {
                val wait = pausedUntil - nowMillis()
                if (wait <= 0) return
                delay(wait)
            }
        }

        /** Starts (or extends) a pause; returns when it ends. */
        fun pause(retryAfterMs: Long?): Long {
            val now = nowMillis()
            val length = retryAfterMs?.coerceIn(5_000L, 15 * 60_000L) ?: backoffMs
            val until = now + length
            if (until > pausedUntil) {
                pausedUntil = until
                backoffMs = (backoffMs * 2).coerceAtMost(10 * 60_000L)
            }
            return pausedUntil
        }

        fun succeeded() {
            backoffMs = 30_000L
        }
    }

    private class RateLimited(val retryAfterMs: Long?) : Exception("rate limited")

    /** Fetches one image, waiting out rate limits. False if it still fails. */
    private suspend fun fetchWithBackoff(
        url: String,
        folder: String,
        gate: RateGate,
        onPause: suspend (Long?) -> Unit,
    ): Boolean {
        repeat(MAX_ATTEMPTS) {
            gate.awaitOpen()
            try {
                save(url, folder)
                gate.succeeded()
                delay(Random.nextLong(250, 750))
                return true
            } catch (e: RateLimited) {
                onPause(gate.pause(e.retryAfterMs))
                gate.awaitOpen()
                onPause(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Network hiccup or a missing image: one quick retry, then give up.
                if (it >= 1) return false
                delay(1_000)
            }
        }
        return false
    }

    /** Copies an image the app already downloaded for viewing; no request needed. */
    private suspend fun saveFromCache(url: String, folder: String): Boolean = runCatching {
        val cache = Images.loader.diskCache ?: return false
        val bytes = cache.openSnapshot(url)?.use { snap ->
            fileSystem.read(snap.data) { readByteArray() }
        } ?: return false
        if (bytes.isEmpty()) return false
        write(url, mimeFor(LocalImages.fileName(url)), folder, bytes)
        true
    }.getOrDefault(false)

    private suspend fun save(url: String, folder: String) {
        val response = Net.client.get(url)
        val code = response.status.value
        if (code == 429 || code == 403 || code == 503) {
            val retryAfter = response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()?.times(1000)
            throw RateLimited(retryAfter)
        }
        if (code !in 200..299) error("HTTP $code")
        val bytes: ByteArray = response.body()
        val name = LocalImages.fileName(url)
        val mime = response.contentType()?.let { "${it.contentType}/${it.contentSubtype}" } ?: mimeFor(name)
        write(url, mime, folder, bytes)
    }

    private suspend fun write(url: String, mime: String, folder: String, bytes: ByteArray) {
        val name = LocalImages.fileName(url).ifEmpty { "image.jpg" }
        val address = sink.write(folder, name, mime, bytes)
        LocalImages.put(name, address)
    }

    private fun mimeFor(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    private fun safeName(name: String) =
        name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifEmpty { "Board" }.take(60)

    private companion object {
        const val WORKERS = 2
        const val MAX_ATTEMPTS = 8
    }
}
