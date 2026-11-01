package com.refboard.app

import android.app.Application
import com.refboard.app.data.EromeSite
import com.refboard.app.data.PornPicsSite
import com.refboard.app.platform.AndroidServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RefBoardApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val galleryDl = PythonGalleryDl(this)
        val site = if (BuildConfig.SITE == "erome") EromeSite else PornPicsSite
        Refboard.start(AndroidServices(this, galleryDl), site)
        // Python (gallery-dl) is only needed for some pages; warm it up off
        // the main thread so it doesn't delay the first screen.
        Refboard.appScope.launch(Dispatchers.IO) { galleryDl.start() }
    }
}
