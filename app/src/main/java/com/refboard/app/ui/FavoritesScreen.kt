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
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.refboard.app.RefBoardApp
import com.refboard.app.data.Favorite
import com.refboard.app.data.FeedSource
import com.refboard.app.data.SiteApi

/** Models and channels you follow, plus a feed mixing their newest galleries. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FavoritesScreen(
    onOpen: (name: String, url: String) -> Unit,
    onOpenLatest: (kind: String?) -> Unit,
    onOpenMenu: () -> Unit,
) {
    val favorites by RefBoardApp.favorites.favorites.collectAsState()
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<Favorite?>(null) }
    val shown = remember(favorites, filter) { favorites.filter { filter == null || it.kind == filter } }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.statusBarsPadding().padding(start = 4.dp, end = 12.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenMenu) { Icon(Icons.Outlined.Menu, "Menu") }
            Text("Favourites", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        }

        if (favorites.isEmpty()) {
            CenterMessage(
                "No favourites yet. Open a gallery and tap the ☆ next to a model or channel to follow them here.",
            )
            return
        }

        Row(
            Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All") })
            FilterChip(
                selected = filter == Favorite.MODEL,
                onClick = { filter = Favorite.MODEL },
                label = { Text("Pornstars") },
            )
            FilterChip(
                selected = filter == Favorite.CHANNEL,
                onClick = { filter = Favorite.CHANNEL },
                label = { Text("Channels") },
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(130.dp),
            contentPadding = PaddingValues(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "latest") {
                FilledTonalButton(
                    onClick = { onOpenLatest(filter) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.DynamicFeed, null, Modifier.size(18.dp))
                    Text("  Latest from ${if (filter == null) "favourites" else "these"}")
                }
            }
            items(shown, key = { it.key }) { fav ->
                // Fetch a cover once: the thumbnail of their newest gallery.
                if (fav.cover == null) {
                    LaunchedEffect(fav.key) {
                        runCatching { SiteApi.listingPage(fav.url, 0).items.firstOrNull { it.thumb != null }?.thumb }
                            .getOrNull()?.let { RefBoardApp.favorites.setCover(fav, it) }
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
                    RefBoardApp.favorites.remove(fav)
                    removing = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

/**
 * Newest galleries across favourites. At most 6 are mixed at once (newest
 * added first) so one page doesn't fire dozens of requests.
 */
fun latestFromFavorites(kind: String?): FeedSource {
    val favorites = RefBoardApp.favorites.favorites.value.filter { kind == null || it.kind == kind }
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
                    if (fav.kind == Favorite.CHANNEL) Icons.Outlined.Videocam else Icons.Outlined.Person,
                    null,
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    if (fav.kind == Favorite.CHANNEL) " Channel" else " Pornstar",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
