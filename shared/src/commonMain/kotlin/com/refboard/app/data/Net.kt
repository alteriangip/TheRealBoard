package com.refboard.app.data

import com.refboard.app.platform.newHttpClient
import com.refboard.app.platform.nowMillis
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object Net {
    const val UA = "Mozilla/5.0 (Linux; Android 14; SM-A155F) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36"
    const val ROOT = "https://www.pornpics.com"

    /** Called when the site starts limiting requests. */
    var onThrottled: ((untilMillis: Long) -> Unit)? = null

    val client: HttpClient by lazy {
        newHttpClient {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 20_000
                socketTimeoutMillis = 40_000
            }
        }.also { client ->
            client.plugin(HttpSend).intercept { request ->
                request.headers[HttpHeaders.UserAgent] = UA
                if (!request.headers.contains(HttpHeaders.Referrer)) request.headers[HttpHeaders.Referrer] = "$ROOT/"
                val host = request.url.host
                Throttle.waitFor(host)
                val call = execute(request)
                val code = call.response.status.value
                if (!Throttle.isLimited(host, code)) {
                    if (code in 200..299) Throttle.succeeded(host)
                    return@intercept call
                }
                val retryAfter = call.response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()?.times(1000)
                Throttle.pause(host, retryAfter)
                Throttle.waitFor(host)
                execute(request)
            }
        }
    }

    /**
     * When a host answers "too many requests", every later request to that
     * host waits until the cooldown is over instead of piling on (which
     * tends to make the cooldown longer). The limited request is retried
     * once after the wait; if it is refused again, the caller sees that.
     */
    private object Throttle {
        private val lock = Mutex()
        private val pausedUntil = HashMap<String, Long>()
        private val backoff = HashMap<String, Long>()
        private const val FIRST_BACKOFF = 5_000L
        private const val MAX_BACKOFF = 120_000L
        private const val MAX_WAIT = 150_000L

        /** 429 and 503 anywhere; the image server also answers 403 when it's limiting. */
        fun isLimited(host: String, code: Int) =
            code == 429 || code == 503 || (code == 403 && host.startsWith("cdni."))

        suspend fun waitFor(host: String) {
            val until = lock.withLock { pausedUntil[host] } ?: return
            val wait = (until - nowMillis()).coerceAtMost(MAX_WAIT)
            if (wait > 0) delay(wait)
        }

        suspend fun succeeded(host: String) = lock.withLock { backoff.remove(host) }

        suspend fun pause(host: String, retryAfter: Long?) {
            val announce = lock.withLock {
                val now = nowMillis()
                val length = retryAfter?.coerceIn(2_000L, MAX_BACKOFF) ?: (backoff[host] ?: FIRST_BACKOFF)
                backoff[host] = ((backoff[host] ?: FIRST_BACKOFF) * 2).coerceAtMost(MAX_BACKOFF)
                val until = now + length
                val previous = pausedUntil[host] ?: 0L
                if (until > previous) {
                    pausedUntil[host] = until
                    // Only announce a new cooldown, not every request that hits it.
                    if (previous < now) until else null
                } else null
            }
            announce?.let { onThrottled?.invoke(it) }
        }
    }
}
