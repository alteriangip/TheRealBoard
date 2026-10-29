package com.refboard.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.refboard.app.RefBoardApp
import com.refboard.app.data.Board

/**
 * Loads [primary]; if that fails and [fallback] is different, tries it instead.
 * Height follows the image so tiles form a masonry layout.
 */
@Composable
fun RemoteImage(
    primary: String?,
    modifier: Modifier = Modifier,
    fallback: String? = null,
    contentScale: ContentScale = ContentScale.FillWidth,
    minHeight: Int = 140,
) {
    var useFallback by remember(primary) { mutableStateOf(false) }
    val model = if (useFallback) fallback else primary
    if (model == null) {
        Box(
            modifier.fillMaxWidth().height(minHeight.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        return
    }
    SubcomposeAsyncImage(
        model = model,
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier,
        loading = {
            Box(
                Modifier.fillMaxWidth().height(minHeight.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        },
        error = {
            if (!useFallback && fallback != null && fallback != primary) {
                LaunchedEffect(model) { useFallback = true }
            }
            Box(
                Modifier.fillMaxWidth().height(minHeight.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.BrokenImage, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

@Composable
fun TagRow(
    tags: List<String>,
    onTag: (String) -> Unit,
    modifier: Modifier = Modifier,
    onLongTag: ((String) -> Unit)? = null,
) {
    if (tags.isEmpty()) return
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(tags, key = { it }) { tag ->
            SuggestionChip(onClick = { onTag(tag) }, label = { Text(tag) })
        }
    }
}

/** Bottom sheet: pick a board or make a new one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardPickerSheet(
    title: String,
    onDismiss: () -> Unit,
    onPicked: (Board) -> Unit,
) {
    val boards by RefBoardApp.boards.boards.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var newName by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text("New board name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = newName.isNotBlank(),
                    onClick = { onPicked(RefBoardApp.boards.create(newName)) },
                ) { Icon(Icons.Outlined.Add, null); Text("Create") }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(boards, key = { it.id }) { board ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPicked(board) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val cover = board.pins.firstOrNull()?.imageUrl
                        Box(
                            Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (cover != null) {
                                RemoteImage(
                                    primary = com.refboard.app.data.Thumbs.small(cover),
                                    fallback = cover,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.aspectRatio(1f),
                                    minHeight = 48,
                                )
                            } else {
                                Icon(Icons.Outlined.Dashboard, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(board.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${board.pins.size} pins",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (boards.isEmpty()) {
                    item {
                        Text(
                            "No boards yet. Name one above.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CenterMessage(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            action()
        }
    }
}

@Composable
fun FilterChipRow(
    options: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    if (options.isEmpty()) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(options, key = { it }) { opt ->
            androidx.compose.material3.FilterChip(
                selected = opt == selected,
                onClick = { onSelect(if (opt == selected) null else opt) },
                label = { Text(opt) },
            )
        }
    }
}

@Composable
fun SmallAssist(text: String, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(text) })
}
