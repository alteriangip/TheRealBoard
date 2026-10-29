package com.refboard.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.refboard.app.ui.BoardDetailScreen
import com.refboard.app.ui.BoardsScreen
import com.refboard.app.ui.DebugScreen
import com.refboard.app.ui.ExploreScreen
import com.refboard.app.ui.ExploreViewModel
import com.refboard.app.ui.GalleryScreen
import com.refboard.app.ui.RefBoardTheme
import com.refboard.app.ui.Session
import com.refboard.app.ui.ViewerScreen

class MainActivity : ComponentActivity() {

    private val sharedUrl = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        sharedUrl.value = extractUrl(intent)
        setContent {
            RefBoardTheme {
                AppRoot(sharedUrl.value) { sharedUrl.value = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractUrl(intent)?.let { sharedUrl.value = it }
    }

    private fun extractUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        return Regex("""https?://\S*pornpics\.com\S*""").find(text)?.value
    }
}

private object Routes {
    const val EXPLORE = "explore"
    const val BOARDS = "boards"
    const val GALLERY = "gallery?url={url}"
    const val VIEWER = "viewer?gallery={gallery}&timer={timer}"
    const val BOARD = "board/{id}"
    const val DEBUG = "debug"

    fun gallery(url: String) = "gallery?url=${Uri.encode(url)}"
    fun viewer(showGallery: Boolean, timerSeconds: Int = 0) = "viewer?gallery=$showGallery&timer=$timerSeconds"
    fun board(id: String) = "board/$id"
}

@Composable
private fun AppRoot(sharedUrl: String?, onSharedHandled: () -> Unit) {
    val nav = rememberNavController()
    val explore: ExploreViewModel = viewModel()
    val snackbar = remember { SnackbarHostState() }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    LaunchedEffect(Unit) {
        Session.messages.collect { msg ->
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(msg)
        }
    }

    LaunchedEffect(sharedUrl) {
        val url = sharedUrl ?: return@LaunchedEffect
        if ("/galleries/" in url) nav.navigate(Routes.gallery(url))
        else {
            explore.openUrl(url)
            nav.goTo(Routes.EXPLORE)
        }
        onSharedHandled()
    }

    fun searchTag(tag: String) {
        explore.openTag(tag)
        nav.goTo(Routes.EXPLORE)
    }

    fun searchText(text: String) {
        explore.search(text)
        nav.goTo(Routes.EXPLORE)
    }

    Scaffold(
        contentWindowInsets = WindowInsets.navigationBars,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (route == Routes.EXPLORE || route == Routes.BOARDS) {
                NavigationBar {
                    NavigationBarItem(
                        selected = route == Routes.EXPLORE,
                        onClick = { nav.goTo(Routes.EXPLORE) },
                        icon = { Icon(Icons.Outlined.Explore, null) },
                        label = { Text("Explore") },
                    )
                    NavigationBarItem(
                        selected = route == Routes.BOARDS,
                        onClick = { nav.goTo(Routes.BOARDS) },
                        icon = { Icon(Icons.Outlined.Dashboard, null) },
                        label = { Text("Boards") },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.EXPLORE, modifier = Modifier.padding(padding)) {
            composable(Routes.EXPLORE) {
                ExploreScreen(
                    vm = explore,
                    onOpenGallery = { nav.navigate(Routes.gallery(it)) },
                    onOpenViewer = { nav.navigate(Routes.viewer(true)) },
                    onOpenDebug = { nav.navigate(Routes.DEBUG) },
                )
            }
            composable(Routes.BOARDS) {
                BoardsScreen(onOpenBoard = { nav.navigate(Routes.board(it)) })
            }
            composable(
                Routes.GALLERY,
                arguments = listOf(navArgument("url") { type = NavType.StringType }),
            ) { entry ->
                val url = entry.arguments?.getString("url").orEmpty()
                GalleryScreen(
                    url = url,
                    onBack = { nav.popBackStack() },
                    onTag = ::searchTag,
                    onSearch = ::searchText,
                    onOpenViewer = { nav.navigate(Routes.viewer(false)) },
                )
            }
            composable(
                Routes.VIEWER,
                arguments = listOf(
                    navArgument("gallery") { type = NavType.BoolType; defaultValue = true },
                    navArgument("timer") { type = NavType.IntType; defaultValue = 0 },
                ),
            ) { entry ->
                ViewerScreen(
                    onBack = { nav.popBackStack() },
                    onTag = ::searchTag,
                    onOpenGallery = { nav.navigate(Routes.gallery(it)) },
                    showGalleryButton = entry.arguments?.getBoolean("gallery") ?: true,
                    practiceSeconds = entry.arguments?.getInt("timer") ?: 0,
                )
            }
            composable(
                Routes.BOARD,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                BoardDetailScreen(
                    boardId = entry.arguments?.getString("id").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onOpenViewer = { nav.navigate(Routes.viewer(true)) },
                    onOpenPractice = { nav.navigate(Routes.viewer(showGallery = false, timerSeconds = it)) },
                )
            }
            composable(Routes.DEBUG) {
                DebugScreen(onBack = { nav.popBackStack() })
            }
        }
    }
}

/** Switch to a top-level tab, dropping anything stacked above it. */
private fun NavHostController.goTo(route: String) {
    navigate(route) {
        popUpTo(Routes.EXPLORE) { saveState = route != Routes.EXPLORE }
        launchSingleTop = true
        restoreState = route != Routes.EXPLORE
    }
}
