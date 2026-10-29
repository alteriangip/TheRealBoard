package com.refboard.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.refboard.app.RefBoardApp
import com.refboard.app.data.FeedItem
import com.refboard.app.data.FeedSource
import com.refboard.app.data.Net
import com.refboard.app.data.Thumbs
import com.refboard.app.data.ViewerImage

private val starterSearches = listOf(
    "standing", "sitting", "kneeling", "lying", "from behind", "side view",
    "close up", "legs", "feet", "hands", "stretching", "yoga", "looking back",
)

@Composable
fun ExploreScreen(
    vm: ExploreViewModel,
    onOpenGallery: (String) -> Unit,
    onOpenViewer: () -> Unit,
    onOpenDebug: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val source = vm.source

    LaunchedEffect(source) {
        if (source is FeedSource.Search) query = source.query
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.statusBarsPadding().padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (source != null) {
                IconButton(onClick = { vm.clear(); query = "" }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                }
            }
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search poses, angles, tags…") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Clear") }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    focus.clearFocus()
                    vm.search(query)
                }),
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            IconButton(onClick = onOpenDebug) { Icon(Icons.Outlined.BugReport, "Debug") }
        }

        if (source == null) {
            HomeSuggestions(
                onSearch = { query = it; vm.search(it) },
                onOpenUrl = { vm.openUrl(it) },
            )
        } else {
            FeedGrid(vm, onOpenGallery, onOpenViewer)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeSuggestions(onSearch: (String) -> Unit, onOpenUrl: (String) -> Unit) {
    val recent by RefBoardApp.prefs.recent.collectAsState()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (recent.isNotEmpty()) {
            SectionLabel("Recent")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                recent.forEach { term ->
                    InputChip(
                        selected = false,
                        onClick = { onSearch(term) },
                        label = { Text(term) },
                        leadingIcon = { Icon(Icons.Outlined.History, null, Modifier.size(16.dp)) },
                        trailingIcon = {
                            Icon(
                                Icons.Outlined.Close, "Remove",
                                Modifier.size(16.dp).clip(RoundedCornerShape(8.dp))
                                    .clickable { RefBoardApp.prefs.forget(term) },
                            )
                        },
                    )
                }
            }
        }
        SectionLabel("Pose & angle ideas")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            starterSearches.forEach { AssistChip(onClick = { onSearch(it) }, label = { Text(it) }) }
        }
        SectionLabel("Browse")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = { onOpenUrl("${Net.ROOT}/popular/") }, label = { Text("Popular") })
            AssistChip(onClick = { onOpenUrl("${Net.ROOT}/recent/") }, label = { Text("Recent") })
            AssistChip(onClick = { onOpenUrl("${Net.ROOT}/rating/") }, label = { Text("Top rated") })
        }
        Text(
            "Tip: tap any tag on a gallery to search it. Long-press a tile to save the whole gallery to a board.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeedGrid(
    vm: ExploreViewModel,
    onOpenGallery: (String) -> Unit,
    onOpenViewer: () -> Unit,
) {
    val gridState = rememberLazyStaggeredGridState()
    var pickerFor by remember { mutableStateOf<String?>(null) }
    val source = vm.source

    LaunchedEffect(gridState, source) {
        snapshotFlow {
            val info = gridState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) to info.totalItemsCount
        }.collect { (last, total) ->
            if (total > 0 && last >= total - 8) vm.loadMore()
        }
    }

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(150.dp),
        state = gridState,
        contentPadding = PaddingValues(10.dp),
        verticalItemSpacing = 8.dp,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = StaggeredGridItemSpan.FullLine, key = "header") {
            Text(
                source?.label ?: "",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
        items(vm.items, key = { it.url }) { item ->
            FeedTile(
                item = item,
                cover = vm.covers[item.url],
                onNeedCover = { vm.resolveCover(item.url) },
                onClick = {
                    if (item.type == "image") {
                        val images = vm.items.filter { it.type == "image" }
                        Session.openViewer(
                            images.map { ViewerImage(it.url, null, it.title, it.tags) },
                            images.indexOf(item),
                        )
                        onOpenViewer()
                    } else {
                        onOpenGallery(item.url)
                    }
                },
                onLongClick = { if (item.type == "gallery") pickerFor = item.url },
            )
        }
        item(span = StaggeredGridItemSpan.FullLine, key = "footer") {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                when {
                    vm.error != null -> CenterMessage(vm.error ?: "") {
                        OutlinedButton(onClick = { vm.retry() }) { Text("Retry") }
                    }
                    vm.loading -> CircularProgressIndicator(Modifier.size(28.dp))
                    vm.done && vm.items.isEmpty() -> CenterMessage("Nothing found. Try fewer or different words.")
                    vm.done -> Text(
                        "That's everything",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    pickerFor?.let { galleryUrl ->
        BoardPickerSheet(
            title = "Save whole gallery to…",
            onDismiss = { pickerFor = null },
            onPicked = { board ->
                pickerFor = null
                vm.saveGallery(galleryUrl, board)
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeedTile(
    item: FeedItem,
    cover: String?,
    onNeedCover: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val full = item.thumb ?: cover
    if (full == null && item.type == "gallery") {
        LaunchedEffect(item.url) { onNeedCover() }
    }
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        RemoteImage(
            primary = full?.let { Thumbs.small(it) },
            fallback = full,
            modifier = Modifier.fillMaxWidth(),
            minHeight = 200,
        )
        if (item.title.isNotBlank()) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}
