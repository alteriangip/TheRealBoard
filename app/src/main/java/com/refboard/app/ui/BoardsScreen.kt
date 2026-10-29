package com.refboard.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.refboard.app.RefBoardApp
import com.refboard.app.data.Board
import com.refboard.app.data.Thumbs
import com.refboard.app.data.ViewerImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardsScreen(onOpenBoard: (String) -> Unit) {
    val boards by RefBoardApp.boards.boards.collectAsState()
    var creating by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Boards") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
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
                "No boards yet. Pin images from a gallery, or make a board here.",
                Modifier.padding(padding),
            )
        } else {
            LazyVerticalGrid(
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
        // 2x2 collage of the newest pins
        val covers = board.pins.take(4).map { it.imageUrl }
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BoardDetailScreen(
    boardId: String,
    onBack: () -> Unit,
    onOpenViewer: () -> Unit,
) {
    val boards by RefBoardApp.boards.boards.collectAsState()
    val board = boards.firstOrNull { it.id == boardId }
    val downloads by RefBoardApp.downloader.states.collectAsState()
    val dl = downloads[boardId]
    var tagFilter by remember { mutableStateOf<String?>(null) }
    val selected = remember { mutableStateListOf<String>() }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (board == null) {
        CenterMessage("Board not found")
        return
    }

    // Tags across the board, most common first: quick filtering for poses.
    val tagCounts = remember(board.pins) {
        board.pins.flatMap { it.tags }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }.take(40)
    }
    val pins = remember(board.pins, tagFilter) {
        val f = tagFilter
        if (f == null) board.pins else board.pins.filter { f in it.tags }
    }
    val selecting = selected.isNotEmpty()

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
                            RefBoardApp.boards.removePins(boardId, selected.toSet())
                            Session.toast("Removed ${selected.size}")
                            selected.clear()
                        }) { Icon(Icons.Outlined.Delete, "Remove from board") }
                    } else {
                        IconButton(
                            enabled = board.pins.isNotEmpty() && (dl == null || dl.finished),
                            onClick = {
                                RefBoardApp.downloader.downloadBoard(board)
                                Session.toast("Saving to Pictures/RefBoard/${board.name}")
                            },
                        ) { Icon(Icons.Outlined.Download, "Download board") }
                        IconButton(onClick = { renaming = true }) { Icon(Icons.Outlined.Edit, "Rename") }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Delete board") }
                    }
                },
            )
        },
    ) { padding ->
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(10.dp),
            verticalItemSpacing = 8.dp,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            item(span = StaggeredGridItemSpan.FullLine, key = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (dl != null) {
                        val progress = if (dl.total == 0) 1f else (dl.done + dl.failed).toFloat() / dl.total
                        Text(
                            if (dl.finished) "Downloaded ${dl.done}/${dl.total}" +
                                (if (dl.failed > 0) " · ${dl.failed} failed" else "")
                            else "Downloading ${dl.done + dl.failed}/${dl.total}…",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        if (dl.finished) {
                            TextButton(onClick = { RefBoardApp.downloader.clear(boardId) }) { Text("Dismiss") }
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
                                    Session.openViewer(
                                        pins.map { ViewerImage(it.imageUrl, it.galleryUrl, it.title, it.tags) },
                                        index,
                                    )
                                    onOpenViewer()
                                }
                            },
                            onLongClick = { if (!selected.remove(pin.imageUrl)) selected.add(pin.imageUrl) },
                        ),
                ) {
                    RemoteImage(primary = Thumbs.small(pin.imageUrl), fallback = pin.imageUrl, minHeight = 220)
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
