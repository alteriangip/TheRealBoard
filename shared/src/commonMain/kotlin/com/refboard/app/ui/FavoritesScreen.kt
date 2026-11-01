package com.refboard.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.refboard.app.Refboard
import com.refboard.app.data.Favorite
import com.refboard.app.data.FeedSource
import com.refboard.app.data.SiteApi

/** Bookmarked models, channels and categories/tags, each with a "latest" feed. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FavoritesScreen(
    onOpen: (name: String, url: String) -> Unit,
    onOpenLatest: (kind: String?) -> Unit,
    onOpenMenu: () -> Unit,
) {
    val favorites by Refboard.favorites.favorites.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var removing by remember { mutableStateOf<Favorite?>(null) }
    val tabs = favoriteTabs
    val kind = tabs[tab.coerceIn(0, tabs.lastIndex)].first
    val shown = remember(favorites, kind) { favorites.filter { it.kind == kind } }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.statusBarsPadding().padding(start = 4.dp, end = 12.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenMenu) { Icon(Icons.Outlined.Menu, "Menu") }
            Text(
                "Favourites",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).clickable { Session.scrollToTop() },
            )
        }

        TabRow(selectedTabIndex = tab.coerceIn(0, tabs.lastIndex)) {
            tabs.forEachIndexed { i, (_, label) ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
            }
        }

        if (shown.isEmpty()) {
            CenterMessage(
                if (kind == Favorite.TOPIC) {
                    "No categories or tags yet. Tap ☆ on a category or tag page, long-press a tile in " +
                        "the Categories tab, or tap ☆ on a gallery's tags."
                } else {
                    "None yet. Open a gallery and tap the ☆ next to a name to bookmark it here."
                },
            )
            return
        }

        val gridState = rememberLazyGridState()
        OnScrollToTop { gridState.animateScrollToItem(0) }
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(130.dp),
            contentPadding = PaddingValues(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "latest") {
                FilledTonalButton(
                    onClick = { onOpenLatest(kind) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.DynamicFeed, null, Modifier.size(18.dp))
                    Text("  Latest from these")
                }
            }
            items(shown, key = { it.key }) { fav ->
                // Fetch a cover once: the thumbnail of their newest gallery.
                if (fav.cover == null) {
                    LaunchedEffect(fav.key) {
                        runCatching { SiteApi.listingPage(fav.url, 0).items.firstOrNull { it.thumb != null }?.thumb }
                            .getOrNull()?.let { Refboard.favorites.setCover(fav, it) }
                    }
                }
                FavoriteTile(
                    fav,
                    onClick = { onOpen(fav.name, fav.url) },
                    onLongClick = { removing = fav },
                )
            }
        }
    }

    removing?.let { fav ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${fav.name}?") },
            text = { Text("They'll no longer appear in Favourites. You can star them again from any of their galleries.") },
            confirmButton = {
                TextButton(onClick = {
                    Refboard.favorites.remove(fav)
                    removing = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

/** Tabs: the favourite kind each shows, and its label. */
private val favoriteTabs get() = buildList {
    add(Favorite.MODEL to Refboard.site.peopleLabel)
    if (Refboard.site.hasChannels) add(Favorite.CHANNEL to "Channels")
    add(Favorite.TOPIC to if (Refboard.site.hasTagIndex) "Categories & tags" else "Tags")
}

/**
 * Newest galleries across favourites. At most 6 are mixed at once (newest
 * added first) so one page doesn't fire dozens of requests.
 */
fun latestFromFavorites(kind: String?): FeedSource {
    val favorites = Refboard.favorites.favorites.value.filter { kind == null || it.kind == kind }
    if (favorites.isEmpty()) return FeedSource.Listing("popular", "Popular")
    val parts = favorites.sortedByDescending { it.addedAt }.take(6)
        .map { FeedSource.Category(it.name, it.url) }
    return if (parts.size == 1) parts.first() else FeedSource.Mix("Latest from favourites", parts)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteTile(fav: Favorite, onClick: () -> Unit, onLongClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        if (fav.cover != null) {
            RemoteImage(
                primary = fav.cover,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                .padding(start = 8.dp, end = 8.dp, top = 24.dp, bottom = 8.dp),
        ) {
            Text(
                fav.name,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (fav.kind) {
                        Favorite.CHANNEL -> Icons.Outlined.Videocam
                        Favorite.TOPIC -> Icons.Outlined.Tag
                        else -> Icons.Outlined.Person
                    },
                    null,
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    when (fav.kind) {
                        Favorite.CHANNEL -> " Channel"
                        Favorite.TOPIC -> " Category / tag"
                        else -> " Pornstar"
                    },
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
