package com.refboard.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.refboard.app.Refboard
import com.refboard.app.data.FeedSource
import com.refboard.app.data.SiteParser
import kotlinx.coroutines.launch

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

    fun gallery(url: String) = "gallery?url=${enc(url)}"
    fun viewer(showGallery: Boolean, timerSeconds: Int = 0) = "viewer?gallery=$showGallery&timer=$timerSeconds"
    fun board(id: String) = "board/$id"
    fun browse(name: String, url: String) = "browse?name=${enc(name)}&url=${enc(url)}"

    /** Route arguments are percent-encoded; spaces as %20 so they decode back exactly. */
    private fun enc(s: String) = SiteParser.encodeComponent(s).replace("+", "%20")

    val tabs = listOf(FEED, EXPLORE, CATEGORIES, FAVORITES, BOARDS)
    fun favLatest(kind: String?) = "favlatest?kind=${kind.orEmpty()}"
}

/**
 * The whole app: tabs, navigation and the side menu. [sharedUrl] is a link
 * shared into the app (Android), opened once and then reported handled.
 */
@Composable
fun App(sharedUrl: String? = null, onSharedHandled: () -> Unit = {}) {
    RefBoardTheme { AppRoot(sharedUrl, onSharedHandled) }
}

@Composable
private fun AppRoot(sharedUrl: String?, onSharedHandled: () -> Unit) {
    val nav = rememberNavController()
    val explore: FeedViewModel = viewModel(key = "explore") { FeedViewModel() }
    val feed: FeedViewModel = viewModel(key = "feed") { FeedViewModel() }
    val snackbar = remember { SnackbarHostState() }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val orientation by Refboard.prefs.orientation.collectAsState()
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
                    Refboard.prefs.setOrientation(o)
                    scope.launch { drawer.close() }
                },
                onOpenDebug = {
                    scope.launch { drawer.close() }
                    nav.navigate(Routes.DEBUG)
                },
            )
        },
    ) {
    Box {
    Scaffold(
        contentWindowInsets = WindowInsets.navigationBars,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (route in Routes.tabs) {
                NavigationBar {
                    NavigationBarItem(
                        selected = route == Routes.FEED,
                        onClick = { if (route == Routes.FEED) Session.scrollToTop() else nav.goTo(Routes.FEED) },
                        icon = { Icon(Icons.Outlined.DynamicFeed, null) },
                        label = { Text("Feed") },
                    )
                    NavigationBarItem(
                        selected = route == Routes.EXPLORE,
                        onClick = { if (route == Routes.EXPLORE) Session.scrollToTop() else nav.goTo(Routes.EXPLORE) },
                        icon = { Icon(Icons.Outlined.Search, null) },
                        label = { Text("Search") },
                    )
                    NavigationBarItem(
                        selected = route == Routes.CATEGORIES,
                        onClick = { if (route == Routes.CATEGORIES) Session.scrollToTop() else nav.goTo(Routes.CATEGORIES) },
                        icon = { Icon(Icons.Outlined.Category, null) },
                        label = { Text("Categories") },
                    )
                    NavigationBarItem(
                        selected = route == Routes.FAVORITES,
                        onClick = { if (route == Routes.FAVORITES) Session.scrollToTop() else nav.goTo(Routes.FAVORITES) },
                        icon = { Icon(Icons.Outlined.StarBorder, null) },
                        label = { Text("Favourites") },
                    )
                    NavigationBarItem(
                        selected = route == Routes.BOARDS,
                        onClick = { if (route == Routes.BOARDS) Session.scrollToTop() else nav.goTo(Routes.BOARDS) },
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
                val kind = entry.savedStateHandle.get<String>("kind").orEmpty().ifEmpty { null }
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
                        entry.savedStateHandle.get<String>("name").orEmpty(),
                        entry.savedStateHandle.get<String>("url").orEmpty(),
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
                val url = entry.savedStateHandle.get<String>("url").orEmpty()
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
                    showGalleryButton = entry.savedStateHandle.get<Boolean>("gallery") ?: true,
                    practiceSeconds = entry.savedStateHandle.get<Int>("timer") ?: 0,
                )
            }
            composable(
                Routes.BOARD,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                BoardDetailScreen(
                    boardId = entry.savedStateHandle.get<String>("id").orEmpty(),
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
    // Like iOS: tapping the very top of the screen scrolls back to the top.
    // (Some phones keep that strip for the system; the screen titles and
    // re-tapping the current tab do the same.)
    if (route?.startsWith("viewer") != true) {
        Box(
            Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    Session.scrollToTop()
                },
        )
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
