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
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material.icons.outlined.Search
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
import com.refboard.app.ui.latestFromFavorites
import com.refboard.app.ui.FavoritesScreen
import androidx.compose.material.icons.outlined.StarBorder
import com.refboard.app.ui.AppMenu
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.DrawerValue
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
import com.refboard.app.ui.FeedViewModel
import com.refboard.app.ui.FeedScreen
import com.refboard.app.ui.CategoriesScreen
import com.refboard.app.ui.BrowseScreen
import com.refboard.app.data.FeedSource
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
    const val FEED = "feed"
    const val EXPLORE = "explore"
    const val CATEGORIES = "categories"
    const val FAVORITES = "favorites"
    const val FAV_LATEST = "favlatest?kind={kind}"
    const val BOARDS = "boards"
    const val BROWSE = "browse?name={name}&url={url}"
    const val GALLERY = "gallery?url={url}"
    const val VIEWER = "viewer?gallery={gallery}&timer={timer}"
    const val BOARD = "board/{id}"
    const val DEBUG = "debug"

    fun gallery(url: String) = "gallery?url=${Uri.encode(url)}"
    fun viewer(showGallery: Boolean, timerSeconds: Int = 0) = "viewer?gallery=$showGallery&timer=$timerSeconds"
    fun board(id: String) = "board/$id"
    fun browse(name: String, url: String) = "browse?name=${Uri.encode(name)}&url=${Uri.encode(url)}"

    val tabs = listOf(FEED, EXPLORE, CATEGORIES, FAVORITES, BOARDS)
    fun favLatest(kind: String?) = "favlatest?kind=${kind.orEmpty()}"
}

@Composable
private fun AppRoot(sharedUrl: String?, onSharedHandled: () -> Unit) {
    val nav = rememberNavController()
    val explore: FeedViewModel = viewModel(key = "explore")
    val feed: FeedViewModel = viewModel(key = "feed")
    val snackbar = remember { SnackbarHostState() }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val orientation by RefBoardApp.prefs.orientation.collectAsState()
    var shownOrientation by rememberSaveable { mutableStateOf(orientation) }
    val openMenu: () -> Unit = { scope.launch { drawer.open() } }

    // Switching content reloads the feeds that depend on it.
    LaunchedEffect(orientation) {
        if (orientation != shownOrientation) {
            shownOrientation = orientation
            feed.orientationChanged(orientation)
            explore.orientationChanged(orientation)
        }
    }

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

    ModalNavigationDrawer(
        drawerState = drawer,
        // Only on the tabs, so it doesn't fight the viewer's swipes.
        gesturesEnabled = drawer.isOpen || route in Routes.tabs,
        drawerContent = {
            AppMenu(
                orientation = orientation,
                onOrientation = { o ->
                    RefBoardApp.prefs.setOrientation(o)
                    scope.launch { drawer.close() }
                },
                onOpenDebug = {
                    scope.launch { drawer.close() }
                    nav.navigate(Routes.DEBUG)
                },
            )
        },
    ) {
    Scaffold(
        contentWindowInsets = WindowInsets.navigationBars,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (route in Routes.tabs) {
                NavigationBar {
                    NavigationBarItem(
                        selected = route == Routes.FEED,
                        onClick = { nav.goTo(Routes.FEED) },
                        icon = { Icon(Icons.Outlined.DynamicFeed, null) },
                        label = { Text("Feed") },
                    )
                    NavigationBarItem(
                        selected = route == Routes.EXPLORE,
                        onClick = { nav.goTo(Routes.EXPLORE) },
                        icon = { Icon(Icons.Outlined.Search, null) },
                        label = { Text("Search") },
                    )
                    NavigationBarItem(
                        selected = route == Routes.CATEGORIES,
                        onClick = { nav.goTo(Routes.CATEGORIES) },
                        icon = { Icon(Icons.Outlined.Category, null) },
                        label = { Text("Categories") },
                    )
                    NavigationBarItem(
                        selected = route == Routes.FAVORITES,
                        onClick = { nav.goTo(Routes.FAVORITES) },
                        icon = { Icon(Icons.Outlined.StarBorder, null) },
                        label = { Text("Favourites") },
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
        NavHost(nav, startDestination = Routes.FEED, modifier = Modifier.padding(padding)) {
            composable(Routes.FEED) {
                FeedScreen(
                    vm = feed,
                    onOpenGallery = { nav.navigate(Routes.gallery(it)) },
                    onOpenViewer = { nav.navigate(Routes.viewer(true)) },
                    onOpenMenu = openMenu,
                )
            }
            composable(Routes.FAVORITES) {
                FavoritesScreen(
                    onOpen = { name, url -> nav.navigate(Routes.browse(name, url)) },
                    onOpenLatest = { kind -> nav.navigate(Routes.favLatest(kind)) },
                    onOpenMenu = openMenu,
                )
            }
            composable(
                Routes.FAV_LATEST,
                arguments = listOf(navArgument("kind") { type = NavType.StringType; defaultValue = "" }),
            ) { entry ->
                val kind = entry.arguments?.getString("kind").orEmpty().ifEmpty { null }
                val source = remember(kind) { latestFromFavorites(kind) }
                BrowseScreen(
                    source = source,
                    onBack = { nav.popBackStack() },
                    onOpenGallery = { nav.navigate(Routes.gallery(it)) },
                    onOpenViewer = { nav.navigate(Routes.viewer(true)) },
                )
            }
            composable(Routes.CATEGORIES) {
                CategoriesScreen(
                    onOpen = { src ->
                        if (src is FeedSource.Category) nav.navigate(Routes.browse(src.name, src.url))
                    },
                    onOpenMenu = openMenu,
                )
            }
            composable(
                Routes.BROWSE,
                arguments = listOf(
                    navArgument("name") { type = NavType.StringType; defaultValue = "" },
                    navArgument("url") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                BrowseScreen(
                    source = FeedSource.Category(
                        entry.arguments?.getString("name").orEmpty(),
                        entry.arguments?.getString("url").orEmpty(),
                    ),
                    onBack = { nav.popBackStack() },
                    onOpenGallery = { nav.navigate(Routes.gallery(it)) },
                    onOpenViewer = { nav.navigate(Routes.viewer(true)) },
                )
            }
            composable(Routes.EXPLORE) {
                ExploreScreen(
                    vm = explore,
                    onOpenGallery = { nav.navigate(Routes.gallery(it)) },
                    onOpenViewer = { nav.navigate(Routes.viewer(true)) },
                    onOpenMenu = openMenu,
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
}

/** Switch to a top-level tab, keeping the other tabs' back stacks. */
private fun NavHostController.goTo(route: String) {
    navigate(route) {
        popUpTo(Routes.FEED) { saveState = true }
        launchSingleTop = true
        // Search always opens on its results, e.g. after tapping a tag.
        restoreState = route != Routes.EXPLORE
    }
}
