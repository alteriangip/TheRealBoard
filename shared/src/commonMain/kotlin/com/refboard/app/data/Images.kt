package com.refboard.app.data

import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import com.refboard.app.platform.Platform

/** The one image loader for the whole app. */
object Images {
    fun install() {
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                // Shares the site client: same headers and rate-limit handling.
                .components {
                    add(KtorNetworkFetcherFactory(httpClient = { Net.client }))
                    // Downloaded pictures load from the device (works offline).
                    add(LocalImages.CoilMapper())
                }
                .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.20).build() }
                .diskCache {
                    DiskCache.Builder()
                        .directory(Platform.services.cacheDir / "image_cache")
                        // Big, so images seen before come from the device, not the site.
                        .maxSizeBytes(800L * 1024 * 1024)
                        .build()
                }
                .crossfade(true)
                .build()
        }
    }

    val loader: ImageLoader get() = SingletonImageLoader.get(Platform.services.imageContext)

    /**
     * Downloads thumbnails into the disk cache before their tiles scroll into
     * view, so they appear at once. Kept out of the memory cache, which the
     * grid fills with correctly sized copies itself.
     */
    fun prefetch(urls: List<String>) {
        val context = Platform.services.imageContext
        urls.forEach { url ->
            loader.enqueue(
                ImageRequest.Builder(context)
                    .data(url)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    // The file is what's wanted; decode it tiny to save the work.
                    .size(Size(64, 64))
                    .build(),
            )
        }
    }
}
