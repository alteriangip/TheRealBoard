package com.refboard.app.data

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object Net {
    const val UA = "Mozilla/5.0 (Linux; Android 14; SM-A155F) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36"
    const val ROOT = "https://www.pornpics.com"

    val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", UA)
                .header("Referer", "$ROOT/")
                .build()
            chain.proceed(req)
        }
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()
}
