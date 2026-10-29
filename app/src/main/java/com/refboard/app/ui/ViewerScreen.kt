package com.refboard.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.refboard.app.RefBoardApp
import com.refboard.app.data.Pin
import com.refboard.app.data.ViewerImage
import kotlinx.coroutines.launch

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ViewerScreen(
    onBack: () -> Unit,
    onTag: (String) -> Unit,
    onOpenGallery: (String) -> Unit,
    showGalleryButton: Boolean,
) {
    val images = remember { Session.viewerImages }
    if (images.isEmpty()) {
        CenterMessage("Nothing to show")
        return
    }
    val pager = rememberPagerState(initialPage = Session.viewerStart) { images.size }
    var zoomed by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(true) }
    var pickFor by remember { mutableStateOf<ViewerImage?>(null) }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            userScrollEnabled = !zoomed,
            beyondViewportPageCount = 1,
            key = { images[it].url },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            ZoomableImage(
                url = images[page].url,
                onZoomChanged = { if (page == pager.currentPage) zoomed = it },
                onTap = { chrome = !chrome },
            )
        }

        val current = images[pager.currentPage]

        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                    .statusBarsPadding().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Color.White) }
                Text(
                    current.title.ifBlank { " " },
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${pager.currentPage + 1}/${images.size}",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }

        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                    .padding(bottom = 12.dp, top = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TagRow(current.tags.take(25), onTag = onTag)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(onClick = { pickFor = current }) {
                        Icon(Icons.Outlined.PushPin, null); Text(" Pin")
                    }
                    FilledTonalButton(onClick = {
                        scope.launch {
                            val ok = RefBoardApp.downloader.saveOne(current.url)
                            Session.toast(if (ok) "Saved to Pictures/RefBoard/Saved" else "Download failed")
                        }
                    }) { Icon(Icons.Outlined.Download, null); Text(" Save") }
                    val gallery = current.galleryUrl
                    if (showGalleryButton && gallery != null) {
                        FilledTonalButton(onClick = { onOpenGallery(gallery) }) {
                            Icon(Icons.Outlined.Collections, null); Text(" Gallery")
                        }
                    }
                }
            }
        }
    }

    pickFor?.let { img ->
        BoardPickerSheet(
            title = "Pin image to…",
            onDismiss = { pickFor = null },
            onPicked = { board ->
                pickFor = null
                val added = RefBoardApp.boards.addPins(
                    board.id, listOf(Pin(img.url, img.galleryUrl, img.title, img.tags)),
                )
                Session.toast(if (added > 0) "Pinned to ${board.name}" else "Already on ${board.name}")
            },
        )
    }
}

/**
 * Pinch to zoom, drag to pan while zoomed, double-tap to toggle 2.5x.
 * One-finger swipes pass through to the pager when not zoomed.
 */
@Composable
private fun ZoomableImage(url: String, onZoomChanged: (Boolean) -> Unit, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    fun setScale(s: Float) {
        val wasZoomed = scale > 1f
        scale = s.coerceIn(1f, 5f)
        if (scale == 1f) offset = Offset.Zero
        if (wasZoomed != (scale > 1f)) onZoomChanged(scale > 1f)
    }

    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { setScale(if (scale > 1f) 1f else 2.5f) },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2 || scale > 1f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            setScale(scale * zoom)
                            if (scale > 1f) {
                                val maxX = size.width * (scale - 1f) / 2f
                                val maxY = size.height * (scale - 1f) / 2f
                                offset = Offset(
                                    (offset.x + pan.x).coerceIn(-maxX, maxX),
                                    (offset.y + pan.y).coerceIn(-maxY, maxY),
                                )
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        SubcomposeAsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            loading = { CircularProgressIndicator(color = Color.White.copy(alpha = 0.6f)) },
            error = { Text("Couldn't load image", color = Color.White.copy(alpha = 0.7f)) },
            modifier = Modifier.fillMaxSize().graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y,
            ),
        )
    }
}
