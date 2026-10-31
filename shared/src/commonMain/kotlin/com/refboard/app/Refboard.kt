package com.refboard.app

import com.refboard.app.data.BoardStore
import com.refboard.app.data.Catalog
import com.refboard.app.data.Downloader
import com.refboard.app.data.FavoritesStore
import com.refboard.app.data.Images
import com.refboard.app.data.LocalImages
import com.refboard.app.data.Net
import com.refboard.app.data.Prefs
import com.refboard.app.platform.Platform
import com.refboard.app.platform.PlatformServices
import com.refboard.app.platform.nowMillis
import com.refboard.app.ui.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The app's shared pieces, created once at startup on Android and iPad alike. */
object Refboard {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var boards: BoardStore
        private set
    lateinit var downloader: Downloader
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var favorites: FavoritesStore
        private set

    private var started = false

    fun start(services: PlatformServices) {
        if (started) return
        started = true
        Platform.services = services
        Images.install()
        boards = BoardStore(services.dataDir, appScope)
        downloader = Downloader()
        prefs = Prefs(services.dataDir, services.legacySettings())
        favorites = FavoritesStore(services.dataDir, appScope)
        Catalog.start(services.dataDir, appScope)
        LocalImages.start(appScope)
        Net.onThrottled = { until ->
            val secs = ((until - nowMillis()) / 1000).coerceAtLeast(1)
            Session.toast("The site is limiting requests. Images will load again in about ${secs}s.")
        }
    }
}
