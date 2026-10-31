package com.refboard.app

import androidx.compose.ui.window.ComposeUIViewController
import com.refboard.app.platform.IosServices
import com.refboard.app.ui.App
import platform.UIKit.UIViewController

/** Called by the iPad app's Swift entry point. */
fun MainViewController(): UIViewController {
    Refboard.start(IosServices())
    return ComposeUIViewController { App() }
}
