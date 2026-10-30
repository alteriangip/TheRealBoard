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
import com.refboard.app.data.Favorite
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.InputChip
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.refboard.app.RefBoardApp
import com.refboard.app.data.Board

/** Width / height of images already seen, so tiles keep their shape when scrolled back to. */
object ImageRatios {
    private val cache = android.util.LruCache<String, Float>(3000)
    fun get(url: String?): Float? = url?.let { cache.get(it) }
    fun put(url: String?, ratio: Float) {
        if (url != null) cache.put(url, ratio)
    }
}

/**
 * Loads [primary]; if that fails and [fallback] is different, tries it instead.
 * With a known [ratio] (from the site, or remembered from an earlier load)
 * the tile has its final size before the image arrives, so the grid doesn't
 * jump while scrolling. Cropped images (covers) fill whatever box they get.
 */
@Composable
fun RemoteImage(
    primary: String?,
    modifier: Modifier = Modifier,
    fallback: String? = null,
    contentScale: ContentScale = ContentScale.FillWidth,
    minHeight: Int = 140,
    ratio: Float? = null,
) {
    var useFallback by remember(primary) { mutableStateOf(false) }
    val model = if (useFallback) fallback else primary
    val known = ratio ?: ImageRatios.get(primary)
    var measured by remember(primary) { mutableStateOf<Float?>(null) }
    val shape = known ?: measured
    val placeholder = MaterialTheme.colorScheme.surfaceVariant
    val painter = remember(placeholder) { ColorPainter(placeholder) }
    val cropped = contentScale == ContentScale.Crop
    val sized = when {
        cropped -> Modifier
        shape != null -> Modifier.fillMaxWidth().aspectRatio(shape)
        else -> Modifier.fillMaxWidth().heightIn(min = minHeight.dp)
    }
    if (model == null) {
        Box(modifier.then(sized).background(placeholder))
        return
    }
    AsyncImage(
        model = model,
        contentDescription = null,
        placeholder = painter,
        error = painter,
        contentScale = if (shape != null && !cropped) ContentScale.Crop else contentScale,
        onSuccess = { state ->
            val size = state.painter.intrinsicSize
            if (size.width > 0f && size.height > 0f) {
                val r = size.width / size.height
                ImageRatios.put(primary, r)
                if (known == null) measured = r
            }
        },
        onError = {
            if (!useFallback && fallback != null && fallback != primary) useFallback = true
        },
        modifier = modifier.then(sized),
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

/** Model or channel chips with a star to follow them in Favourites. */
@Composable
fun FavoriteChipRow(names: List<String>, kind: String, onOpen: (String) -> Unit) {
    val favorites by RefBoardApp.favorites.favorites.collectAsState()
    val keys = remember(favorites) { favorites.mapTo(HashSet()) { it.key } }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(names, key = { it }) { name ->
            val starred = Favorite.key(kind, name) in keys
            InputChip(
                selected = starred,
                onClick = { onOpen(name) },
                label = { Text(name) },
                trailingIcon = {
                    Icon(
                        if (starred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        if (starred) "Remove from favourites" else "Add to favourites",
                        Modifier.size(20.dp).clip(CircleShape).clickable {
                            val added = RefBoardApp.favorites.toggle(kind, name)
                            Session.toast(if (added) "Added $name to Favourites" else "Removed $name from Favourites")
                        },
                    )
                },
            )
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
    excludeBoardId: String? = null,
) {
    val allBoards by RefBoardApp.boards.boards.collectAsState()
    val boards = allBoards.filter { it.id != excludeBoardId }
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
                        val cover = board.cover ?: board.pins.firstOrNull()?.imageUrl
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
