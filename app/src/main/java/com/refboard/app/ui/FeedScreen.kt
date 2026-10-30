package com.refboard.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.refboard.app.data.FeedSource

/** The feeds on offer: "For you" plus the site's own listings. */
private val listings = listOf(
    FeedSource.Listing("popular", "Popular"),
    FeedSource.Listing("recent", "Newest"),
    FeedSource.Listing("rating", "Top rated"),
    FeedSource.Listing("likes", "Most liked"),
    FeedSource.Listing("views", "Most viewed"),
)

@Composable
fun FeedScreen(
    vm: FeedViewModel,
    onOpenGallery: (String) -> Unit,
    onOpenViewer: () -> Unit,
) {
    LaunchedEffect(Unit) {
        if (vm.source == null) vm.openForYou()
    }
    val source = vm.source
    val forYou = source == null || source is FeedSource.Mix

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.statusBarsPadding().padding(start = 16.dp, end = 4.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Feed", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                if (forYou) vm.openForYou(fresh = true) else vm.retry()
            }) { Icon(Icons.Outlined.Refresh, "Refresh") }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "foryou") {
                FilterChip(selected = forYou, onClick = { vm.openForYou() }, label = { Text("For you") })
            }
            items(listings, key = { it.kind }) { listing ->
                FilterChip(
                    selected = source == listing,
                    onClick = { vm.show(listing) },
                    label = { Text(listing.title) },
                )
            }
        }
        if (forYou) {
            Text(
                "Mixed from the tags you pin, your recent searches and a few categories.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        FeedGrid(vm, onOpenGallery, onOpenViewer, showHeader = false)
    }
}
