package com.refboard.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.refboard.app.RefBoardApp
import com.refboard.app.data.Board
import com.refboard.app.data.Bridge
import com.refboard.app.data.GalleryData
import com.refboard.app.data.Pin
import com.refboard.app.data.Catalog
import com.refboard.app.data.Favorite
import com.refboard.app.data.Orientation
import com.refboard.app.data.SiteParser
import com.refboard.app.data.Thumbs
import com.refboard.app.data.ViewerImage
import kotlinx.coroutines.launch

class GalleryViewModel(private val url: String) : ViewModel() {
    var data by mutableStateOf<GalleryData?>(Bridge.cachedGallery(url))
        private set
    var error by mutableStateOf<String?>(null)
        private set
    val selected = mutableStateListOf<String>()

    init {
        if (data == null) load()
    }

    fun load() {
        error = null
        viewModelScope.launch {
            runCatching { Bridge.gallery(url) }
                .onSuccess {
                    data = it
                    prefetch(it.images.take(40).map { img -> img.thumb ?: Thumbs.small(img.url) })
                }
                .onFailure { error = it.message ?: "Couldn't open gallery" }
        }
    }

    fun toggle(imageUrl: String) {
        if (!selected.remove(imageUrl)) selected.add(imageUrl)
    }

    fun selectAll() {
        val all = data?.images?.map { it.url } ?: return
        selected.clear()
        selected.addAll(all)
    }

    fun pinSelected(board: Board) {
        val d = data ?: return
        val ratios = d.images.associate { it.url to it.ratio }
        val pins = selected.map { Pin(it, url, d.title, d.tags, ratio = ratios[it]) }
        val added = RefBoardApp.boards.addPins(board.id, pins)
        Session.toast("Added $added to ${board.name}")
        selected.clear()
    }

    fun pinAll(board: Board) {
        viewModelScope.launch { saveGalleryToBoard(url, board) }
    }
}

private val groupLabels = listOf(
    "models" to "Models",
    "channel" to "Channel",
    "categories" to "Categories",
    "tags" to "Tags",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    url: String,
    onBack: () -> Unit,
    onTag: (String) -> Unit,
    onSearch: (String) -> Unit,
    onOpenViewer: () -> Unit,
) {
    val vm: GalleryViewModel = viewModel(key = url) { GalleryViewModel(url) }
    val context = LocalContext.current
    val data = vm.data
    val selecting = vm.selected.isNotEmpty()
    var picker by remember { mutableStateOf<String?>(null) } // "all" or "selected"

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    if (selecting) {
                        IconButton(onClick = { vm.selected.clear() }) { Icon(Icons.Outlined.Close, "Cancel") }
                    } else {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                    }
                },
                title = {
                    Text(
                        if (selecting) "${vm.selected.size} selected" else data?.title ?: "Gallery",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    if (selecting) {
                        IconButton(onClick = { vm.selectAll() }) { Icon(Icons.Outlined.SelectAll, "Select all") }
                        IconButton(onClick = { picker = "selected" }) { Icon(Icons.Outlined.PushPin, "Pin selected") }
                    } else {
                        IconButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }) { Icon(Icons.Outlined.OpenInBrowser, "Open in browser") }
                        IconButton(enabled = data != null, onClick = { picker = "all" }) {
                            Icon(Icons.Outlined.PushPin, "Pin whole gallery")
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            data == null && vm.error != null -> CenterMessage(vm.error ?: "", Modifier.padding(padding)) {
                OutlinedButton(onClick = { vm.load() }) { Text("Retry") }
            }
            data == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(10.dp),
                verticalItemSpacing = 8.dp,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                item(span = StaggeredGridItemSpan.FullLine, key = "tags") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        groupLabels.forEach { (key, label) ->
                            val values = data.groups[key].orEmpty()
                            if (values.isNotEmpty()) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                                )
                                when (key) {
                                    "models" -> FavoriteChipRow(values, Favorite.MODEL, onOpen = onSearch)
                                    "channel" -> FavoriteChipRow(values, Favorite.CHANNEL, onOpen = onSearch)
                                    "categories" -> FavoriteChipRow(values, Favorite.TOPIC, onOpen = onTag) { name ->
                                        Catalog.categoryNamed(name, RefBoardApp.prefs.orientation.value)?.url
                                            ?: Catalog.categoryNamed(name, Orientation.Straight)?.url
                                            ?: SiteParser.searchUrl(name)
                                    }
                                    "tags" -> FavoriteChipRow(values, Favorite.TOPIC, onOpen = onTag) { name ->
                                        SiteParser.tagUrl(name)
                                    }
                                    else -> TagRow(values, onTag = onTag)
                                }
                            }
                        }
                        if (data.groups.isEmpty() && data.tags.isNotEmpty()) {
                            TagRow(data.tags, onTag = onTag)
                        }
                        Text(
                            "${data.images.size} images · long-press to select",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                        )
                    }
                }
                itemsIndexed(data.images, key = { _, img -> img.url }) { index, img ->
                    val isSelected = img.url in vm.selected
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .then(
                                if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                                else Modifier
                            )
                            .combinedClickable(
                                onClick = {
                                    if (selecting) vm.toggle(img.url) else {
                                        Session.openViewer(
                                            data.images.map { ViewerImage(it.url, url, data.title, data.tags) },
                                            index,
                                        )
                                        onOpenViewer()
                                    }
                                },
                                onLongClick = { vm.toggle(img.url) },
                            ),
                    ) {
                        RemoteImage(primary = img.thumb ?: Thumbs.small(img.url), fallback = img.url, minHeight = 220, ratio = img.ratio)
                        if (isSelected) {
                            Icon(
                                Icons.Outlined.CheckCircle, null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                            )
                        }
                    }
                }
            }
        }
    }

    picker?.let { mode ->
        BoardPickerSheet(
            title = if (mode == "all") "Pin whole gallery to…" else "Pin ${vm.selected.size} images to…",
            onDismiss = { picker = null },
            onPicked = { board ->
                picker = null
                if (mode == "all") vm.pinAll(board) else vm.pinSelected(board)
            },
        )
    }
}
