package com.refboard.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.refboard.app.RefBoardApp
import com.refboard.app.data.Board
import com.refboard.app.data.Pin
import com.refboard.app.data.Thumbs
import com.refboard.app.data.ViewerImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardsScreen(onOpenBoard: (String) -> Unit) {
    val boards by RefBoardApp.boards.boards.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use {
                        it.write(RefBoardApp.boards.exportJson(RefBoardApp.favorites.favorites.value).toByteArray())
                    }
                }.isSuccess
            }
            Session.toast(if (ok) "Boards backed up" else "Backup failed")
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                    RefBoardApp.boards.importJson(text)
                }
            }
            result.onSuccess { r ->
                val favs = RefBoardApp.favorites.merge(r.favorites)
                Session.toast("Restored ${r.boards} boards, ${r.pins} pins and $favs favourites")
            }.onFailure { Session.toast("Couldn't restore: ${it.message}") }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Boards") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Back up boards") },
                                enabled = boards.isNotEmpty(),
                                onClick = {
                                    menu = false
                                    val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                                    exportLauncher.launch("refboard-backup-$stamp.json")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Restore from backup") },
                                onClick = {
                                    menu = false
                                    importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Outlined.Add, null) },
                text = { Text("New board") },
            )
        },
    ) { padding ->
        if (boards.isEmpty()) {
            CenterMessage(
                "No boards yet. Pin images from a gallery, make a board here, or restore a backup from the ⋮ menu.",
                Modifier.padding(padding),
            )
        } else {
            val gridState = rememberLazyGridState()
            OnScrollToTop { gridState.animateScrollToItem(0) }
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                items(boards, key = { it.id }) { board -> BoardCard(board) { onOpenBoard(board.id) } }
            }
        }
    }

    if (creating) {
        NameDialog(
            title = "New board",
            initial = "",
            onDismiss = { creating = false },
            onConfirm = { RefBoardApp.boards.create(it); creating = false },
        )
    }
}

@Composable
private fun BoardCard(board: Board, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        // The chosen cover, or a 2x2 collage of the newest pins
        val covers = board.cover?.let { listOf(it) } ?: board.pins.take(4).map { it.imageUrl }
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.85f).clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            when {
                covers.isEmpty() -> Icon(Icons.Outlined.Dashboard, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                covers.size < 4 -> CoverImage(covers[0], Modifier.fillMaxSize())
                else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (row in 0..1) {
                        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            for (col in 0..1) CoverImage(covers[row * 2 + col], Modifier.weight(1f).fillMaxSize())
                        }
                    }
                }
            }
        }
        Text(
            board.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp),
        )
        Text(
            "${board.pins.size} pins",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}

@Composable
private fun CoverImage(url: String, modifier: Modifier) {
    RemoteImage(
        primary = Thumbs.small(url),
        fallback = url,
        contentScale = ContentScale.Crop,
        modifier = modifier,
        minHeight = 60,
    )
}

private enum class PinSort(val label: String) {
    Newest("Newest first"),
    Oldest("Oldest first"),
    Gallery("Grouped by gallery"),
}

private fun sortPins(pins: List<Pin>, sort: PinSort): List<Pin> = when (sort) {
    PinSort.Newest -> pins.sortedByDescending { it.addedAt }
    PinSort.Oldest -> pins.sortedBy { it.addedAt }
    // Galleries in order of their newest pin, images in gallery order.
    PinSort.Gallery -> pins.sortedByDescending { it.addedAt }
        .groupBy { it.galleryUrl ?: it.imageUrl }
        .values.flatMap { group -> group.sortedBy { it.imageUrl } }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BoardDetailScreen(
    boardId: String,
    onBack: () -> Unit,
    onOpenViewer: () -> Unit,
    onOpenPractice: (seconds: Int) -> Unit,
) {
    val boards by RefBoardApp.boards.boards.collectAsState()
    val board = boards.firstOrNull { it.id == boardId }
    val downloads by RefBoardApp.downloader.states.collectAsState()
    val dl = downloads[boardId]
    var tagFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(PinSort.Newest) }
    val selected = remember { mutableStateListOf<String>() }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var practicing by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Ask once for notifications so the download progress shows; the
    // download starts either way.
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        board?.let { RefBoardApp.downloader.downloadBoard(it) }
    }

    if (board == null) {
        CenterMessage("Board not found")
        return
    }

    // Tags across the board, most common first: quick filtering for poses.
    val tagCounts = remember(board.pins) {
        board.pins.flatMap { it.tags }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }.take(40)
    }
    val pins = remember(board.pins, tagFilter, sort) {
        val f = tagFilter
        sortPins(if (f == null) board.pins else board.pins.filter { f in it.tags }, sort)
    }
    val selecting = selected.isNotEmpty()

    fun startDownload() {
        Session.toast("Saving to Pictures/RefBoard/${board.name}")
        val needsAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsAsk) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else RefBoardApp.downloader.downloadBoard(board)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    if (selecting) IconButton(onClick = { selected.clear() }) { Icon(Icons.Outlined.Close, "Cancel") }
                    else IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                },
                title = {
                    Text(
                        if (selecting) "${selected.size} selected" else board.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    if (selecting) {
                        IconButton(onClick = {
                            selected.clear()
                            selected.addAll(pins.map { it.imageUrl })
                        }) { Icon(Icons.Outlined.SelectAll, "Select all") }
                        if (selected.size == 1) {
                            IconButton(onClick = {
                                RefBoardApp.boards.setCover(boardId, selected.first())
                                Session.toast("Cover updated")
                                selected.clear()
                            }) { Icon(Icons.Outlined.Wallpaper, "Set as cover") }
                        }
                        IconButton(onClick = { moving = true }) {
                            Icon(Icons.Outlined.DriveFileMove, "Move to another board")
                        }
                        IconButton(onClick = {
                            RefBoardApp.boards.removePins(boardId, selected.toSet())
                            Session.toast("Removed ${selected.size}")
                            selected.clear()
                        }) { Icon(Icons.Outlined.Delete, "Remove from board") }
                    } else {
                        IconButton(enabled = pins.isNotEmpty(), onClick = { practicing = true }) {
                            Icon(Icons.Outlined.Timer, "Timed practice")
                        }
                        if (dl != null && !dl.finished) {
                            IconButton(onClick = { RefBoardApp.downloader.cancel(boardId) }) {
                                Icon(Icons.Outlined.StopCircle, "Stop download")
                            }
                        } else {
                            IconButton(enabled = board.pins.isNotEmpty(), onClick = ::startDownload) {
                                Icon(Icons.Outlined.Download, "Download board")
                            }
                        }
                        Box {
                            IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Outlined.Sort, "Sort") }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                PinSort.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label) },
                                        leadingIcon = {
                                            if (option == sort) Icon(Icons.Outlined.CheckCircle, null, Modifier.size(18.dp))
                                        },
                                        onClick = { sort = option; sortMenu = false },
                                    )
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { moreMenu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                            DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                                DropdownMenuItem(text = { Text("Rename") }, onClick = { moreMenu = false; renaming = true })
                                if (board.cover != null) {
                                    DropdownMenuItem(
                                        text = { Text("Reset cover") },
                                        onClick = { moreMenu = false; RefBoardApp.boards.setCover(boardId, null) },
                                    )
                                }
                                DropdownMenuItem(text = { Text("Delete board") }, onClick = { moreMenu = false; confirmDelete = true })
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        val gridState = rememberLazyStaggeredGridState()
        OnScrollToTop { gridState.animateScrollToItem(0) }
        LazyVerticalStaggeredGrid(
            state = gridState,
            columns = StaggeredGridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(10.dp),
            verticalItemSpacing = 8.dp,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            item(span = StaggeredGridItemSpan.FullLine, key = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (dl != null) {
                        val progress = if (dl.total == 0) 1f else dl.handled.toFloat() / dl.total
                        val extra = buildString {
                            if (dl.skipped > 0) append(" · ${dl.skipped} already saved")
                            if (dl.fromCache > 0) append(" · ${dl.fromCache} from cache")
                            if (dl.failed > 0) append(" · ${dl.failed} failed")
                        }
                        Text(
                            when {
                                dl.cancelled -> "Stopped at ${dl.handled}/${dl.total}$extra"
                                dl.finished -> "Downloaded ${dl.done}/${dl.total}$extra"
                                dl.pausedUntil != null ->
                                    "Paused at ${dl.handled}/${dl.total}: the site asked to slow down. Resuming shortly.$extra"
                                else -> "Downloading ${dl.handled}/${dl.total}…$extra"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        if (dl.finished) {
                            Row {
                                if (dl.failed > 0 || dl.cancelled) {
                                    // Saved images are skipped, so this only fetches what's missing.
                                    TextButton(onClick = ::startDownload) {
                                        Text(if (dl.cancelled) "Resume" else "Retry ${dl.failed} failed")
                                    }
                                }
                                TextButton(onClick = { RefBoardApp.downloader.clear(boardId) }) { Text("Dismiss") }
                            }
                        }
                    }
                    FilterChipRow(tagCounts, tagFilter) { tagFilter = it }
                }
            }
            if (pins.isEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine, key = "empty") {
                    CenterMessage("Nothing here yet.")
                }
            }
            itemsIndexed(pins, key = { _, p -> p.imageUrl }) { index, pin ->
                val isSelected = pin.imageUrl in selected
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .then(
                            if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                            else Modifier
                        )
                        .combinedClickable(
                            onClick = {
                                if (selecting) {
                                    if (!selected.remove(pin.imageUrl)) selected.add(pin.imageUrl)
                                } else {
                                    Session.openViewer(pins.map { it.toViewerImage() }, index)
                                    onOpenViewer()
                                }
                            },
                            onLongClick = { if (!selected.remove(pin.imageUrl)) selected.add(pin.imageUrl) },
                        ),
                ) {
                    RemoteImage(primary = Thumbs.small(pin.imageUrl), fallback = pin.imageUrl, minHeight = 220, ratio = pin.ratio)
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

    if (renaming) {
        NameDialog(
            title = "Rename board",
            initial = board.name,
            onDismiss = { renaming = false },
            onConfirm = { RefBoardApp.boards.rename(boardId, it); renaming = false },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${board.name}\"?") },
            text = { Text("The board and its pins are removed from the app. Images already downloaded to your phone stay.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    RefBoardApp.boards.delete(boardId)
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    if (practicing) {
        PracticeDialog(
            count = pins.size,
            onDismiss = { practicing = false },
            onStart = { seconds, shuffle, limit ->
                practicing = false
                val order = if (shuffle) pins.shuffled() else pins
                Session.openViewer(order.take(limit).map { it.toViewerImage() }, 0)
                onOpenPractice(seconds)
            },
        )
    }
    if (moving) {
        val count = selected.size
        BoardPickerSheet(
            title = "Move $count images to…",
            excludeBoardId = boardId,
            onDismiss = { moving = false },
            onPicked = { target ->
                moving = false
                RefBoardApp.boards.movePins(boardId, target.id, selected.toSet())
                Session.toast("Moved $count to ${target.name}")
                selected.clear()
            },
        )
    }
}

private fun Pin.toViewerImage() = ViewerImage(imageUrl, galleryUrl, title, tags)

private val practiceTimes = listOf(30, 60, 120, 300, 600)
private val practiceCounts = listOf(5, 10, 20, 40)

/** Options for a timed gesture-drawing session. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PracticeDialog(count: Int, onDismiss: () -> Unit, onStart: (seconds: Int, shuffle: Boolean, limit: Int) -> Unit) {
    var seconds by rememberSaveable { mutableIntStateOf(60) }
    var limit by rememberSaveable { mutableIntStateOf(Int.MAX_VALUE) }
    var shuffle by rememberSaveable { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Timed practice") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Time per image", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    practiceTimes.forEach { s ->
                        FilterChip(selected = s == seconds, onClick = { seconds = s }, label = { Text(formatClock(s)) })
                    }
                }
                Text("Images", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    practiceCounts.filter { it < count }.forEach { n ->
                        FilterChip(selected = n == limit, onClick = { limit = n }, label = { Text("$n") })
                    }
                    FilterChip(
                        selected = limit >= count,
                        onClick = { limit = Int.MAX_VALUE },
                        label = { Text("All $count") },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { shuffle = !shuffle }) {
                    Checkbox(checked = shuffle, onCheckedChange = { shuffle = it })
                    Text("Shuffle")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onStart(seconds, shuffle, limit) }) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, placeholder = { Text("Board name") })
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
