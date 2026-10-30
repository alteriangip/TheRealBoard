package com.refboard.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.refboard.app.RefBoardApp
import com.refboard.app.data.Catalog
import com.refboard.app.data.FeedSource
import com.refboard.app.data.TagEntry

/** Every category with its sample image, and every tag on the site. */
@Composable
fun CategoriesScreen(onOpen: (FeedSource) -> Unit, onOpenMenu: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val orientation by RefBoardApp.prefs.orientation.collectAsState()
    val allCategories by Catalog.categories.collectAsState()
    val allTags by Catalog.tags.collectAsState()
    val categories = allCategories[orientation].orEmpty()
    val tags = allTags[orientation].orEmpty()
    val samples by Catalog.samples.collectAsState()

    LaunchedEffect(tab, attempt, orientation) {
        error = null
        runCatching {
            if (tab == 0) Catalog.loadCategories(orientation, force = attempt > 0)
            else Catalog.loadTags(orientation, force = attempt > 0)
        }.onFailure { error = it.message ?: "Couldn't load" }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onOpenMenu) { Icon(Icons.Outlined.Menu, "Menu") }
            TabRow(selectedTabIndex = tab, modifier = Modifier.weight(1f)) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Categories") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Tags") })
            }
        }
        TextField(
            value = filter,
            onValueChange = { filter = it },
            placeholder = { Text(if (tab == 0) "Filter categories" else "Filter ${tags.size.takeIf { it > 0 } ?: ""} tags") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = {
                if (filter.isNotEmpty()) IconButton(onClick = { filter = "" }) { Icon(Icons.Outlined.Close, "Clear") }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )

        val needle = filter.trim()
        val loaded = if (tab == 0) categories.isNotEmpty() else tags.isNotEmpty()
        when {
            !loaded && error != null -> CenterMessage(error ?: "") {
                OutlinedButton(onClick = { attempt++ }) { Text("Retry") }
            }
            !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            tab == 0 -> {
                val shown = remember(categories, needle) {
                    categories.filter { needle.isEmpty() || it.name.contains(needle, ignoreCase = true) }
                }
                TileGrid(empty = shown.isEmpty()) {
                    items(shown, key = { it.url }) { cat ->
                        // Gay and trans categories come without images: fetch one.
                        if (cat.thumb == null) LaunchedEffect(cat.url) { Catalog.loadSample(cat.url) }
                        NamedTile(cat.name, cat.thumb ?: samples[cat.url]) {
                            onOpen(FeedSource.Category(cat.name, cat.url))
                        }
                    }
                }
            }
            else -> {
                val shown = remember(tags, needle) {
                    tags.filter { needle.isEmpty() || it.name.contains(needle, ignoreCase = true) }
                }
                TileGrid(empty = shown.isEmpty()) {
                    items(shown, key = { it.slug }) { tag ->
                        // Load the sample while the tile is on screen; scrolling
                        // past cancels requests that haven't started yet.
                        LaunchedEffect(tag.url) { Catalog.loadSample(tag.url) }
                        NamedTile(tag.name, samples[tag.url]) { onOpen(tag.toSource()) }
                    }
                }
            }
        }
    }
}

// Opened by its exact link from the tag list, like a category page.
private fun TagEntry.toSource() = FeedSource.Category(name, url)

@Composable
private fun TileGrid(
    empty: Boolean,
    content: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit,
) {
    if (empty) {
        CenterMessage("No matches.")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        state = rememberLazyGridState(),
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
        content = content,
    )
}

/** Sample image with the name on a dark gradient, like the site's category tiles. */
@Composable
private fun NamedTile(name: String, image: String?, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        if (image != null) {
            RemoteImage(
                primary = image,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                minHeight = 160,
            )
        } else {
            Icon(
                Icons.Outlined.Tag, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                .padding(start = 8.dp, end = 8.dp, top = 22.dp, bottom = 8.dp),
        ) {
            Text(
                name,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A single category or tag, opened from the Categories tab. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    source: FeedSource,
    onBack: () -> Unit,
    onOpenGallery: (String) -> Unit,
    onOpenViewer: () -> Unit,
) {
    val vm: FeedViewModel = viewModel(key = "browse:$source")
    LaunchedEffect(source) { vm.show(source) }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                title = { Text(vm.source?.label ?: source.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            FeedGrid(vm, onOpenGallery, onOpenViewer, showHeader = false)
        }
    }
}
