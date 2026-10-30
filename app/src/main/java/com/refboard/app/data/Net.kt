package com.refboard.app.data

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object Net {
    const val UA = "Mozilla/5.0 (Linux; Android 14; SM-A155F) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36"
    const val ROOT = "https://www.pornpics.com"

    /** Called (from a network thread) when the site starts limiting requests. */
    @Volatile var onThrottled: ((untilMillis: Long) -> Unit)? = null

    val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val original = chain.request()
            val req = original.newBuilder()
                .header("User-Agent", UA)
                .apply { if (original.header("Referer") == null) header("Referer", "$ROOT/") }
                .build()
            chain.proceed(req)
        }
        .addInterceptor(Throttle)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    /**
     * When a host answers "too many requests", every later request to that
     * host waits until the cooldown is over instead of piling on (which
     * tends to make the cooldown longer). The limited request is retried
     * once after the wait; if it is refused again, the caller sees that.
     */
    private object Throttle : Interceptor {
        private val pausedUntil = ConcurrentHashMap<String, Long>()
        private val backoff = ConcurrentHashMap<String, Long>()
        private const val FIRST_BACKOFF = 5_000L
        private const val MAX_BACKOFF = 120_000L
        private const val MAX_WAIT = 150_000L

        override fun intercept(chain: Interceptor.Chain): Response {
            val host = chain.request().url.host
            waitFor(host)
            val response = chain.proceed(chain.request())
            if (!isLimited(host, response.code)) {
                if (response.isSuccessful) backoff.remove(host)
                return response
            }
            val retryAfter = response.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000)
            response.close()
            pause(host, retryAfter)
            waitFor(host)
            return chain.proceed(chain.request())
        }

        /** 429 and 503 anywhere; the image server also answers 403 when it's limiting. */
        private fun isLimited(host: String, code: Int) =
            code == 429 || code == 503 || (code == 403 && host.startsWith("cdni."))

        private fun waitFor(host: String) {
            val wait = ((pausedUntil[host] ?: return) - System.currentTimeMillis()).coerceAtMost(MAX_WAIT)
            if (wait > 0) Thread.sleep(wait)
        }

        private fun pause(host: String, retryAfter: Long?) {
            val now = System.currentTimeMillis()
            val length = retryAfter?.coerceIn(2_000L, MAX_BACKOFF) ?: (backoff[host] ?: FIRST_BACKOFF)
            backoff[host] = ((backoff[host] ?: FIRST_BACKOFF) * 2).coerceAtMost(MAX_BACKOFF)
            val until = now + length
            val previous = pausedUntil[host] ?: 0L
            if (until > previous) {
                pausedUntil[host] = until
                // Only announce a new cooldown, not every request that hits it.
                if (previous < now) onThrottled?.invoke(until)
            }
        }
    }
}
