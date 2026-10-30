package com.refboard.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.refboard.app.data.BoardStore
import com.refboard.app.data.Catalog
import com.refboard.app.data.Downloader
import com.refboard.app.data.Net
import com.refboard.app.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RefBoardApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        instance = this
        boards = BoardStore(this, appScope)
        downloader = Downloader(this)
        prefs = Prefs(this)
        Catalog.start(this, appScope)
        // Python (gallery-dl) is only needed for some pages; warm it up off
        // the main thread so it doesn't delay the first screen.
        appScope.launch(Dispatchers.IO) { startPython() }
    }

    // One image loader for the whole app: sends the site's Referer, keeps a
    // capped disk cache so scrolling back is instant without filling storage.
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(Net.client)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("images"))
                    .maxSizeBytes(300L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .crossfade(true)
            .build()

    companion object {
        lateinit var instance: RefBoardApp
            private set

        @Synchronized
        fun startPython() {
            if (!Python.isStarted()) Python.start(AndroidPlatform(instance))
        }
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        lateinit var boards: BoardStore
            private set
        lateinit var downloader: Downloader
            private set
        lateinit var prefs: Prefs
            private set
    }
}
