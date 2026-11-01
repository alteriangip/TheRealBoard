package com.refboard.app

import androidx.compose.ui.window.ComposeUIViewController
import com.refboard.app.data.EromeSite
import com.refboard.app.data.PornPicsSite
import com.refboard.app.platform.IosServices
import com.refboard.app.ui.App
import platform.UIKit.UIViewController

/** Called by each iPad app's Swift entry point with the site it browses ("pornpics" or "erome"). */
fun MainViewController(site: String): UIViewController {
    Refboard.start(IosServices(), if (site == "erome") EromeSite else PornPicsSite)
    return ComposeUIViewController { App() }
}
