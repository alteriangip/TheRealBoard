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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FilterBAndW
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.refboard.app.Refboard
import com.refboard.app.data.Pin
import com.refboard.app.data.Thumbs
import com.refboard.app.data.ViewerImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

/** Study aids that apply to every image in the viewer. */
private data class ViewTools(
    val mirror: Boolean = false,
    val grey: Boolean = false,
    /** 0 = off, otherwise the number of cells per side (3 = thirds). */
    val grid: Int = 0,
    /** Clockwise quarter turns. */
    val rotation: Int = 0,
)

/**
 * Full-screen viewer. With [practiceSeconds] > 0 it runs a timed
 * gesture-drawing session that moves to the next image when time is up.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ViewerScreen(
    onBack: () -> Unit,
    onTag: (String) -> Unit,
    onOpenGallery: (String) -> Unit,
    showGalleryButton: Boolean,
    practiceSeconds: Int = 0,
) {
    val images = remember {
        Session.restoreViewer()
        Session.viewerImages
    }
    if (images.isEmpty()) {
        CenterMessage("Nothing to show")
        return
    }
    val pager = rememberPagerState(initialPage = Session.viewerStart) { images.size }
    var zoomed by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(true) }
    var pickFor by remember { mutableStateOf<ViewerImage?>(null) }
    val scope = rememberCoroutineScope()

    var mirror by rememberSaveable { mutableStateOf(false) }
    var grey by rememberSaveable { mutableStateOf(false) }
    var grid by rememberSaveable { mutableIntStateOf(0) }
    var rotation by rememberSaveable { mutableIntStateOf(0) }
    val tools = ViewTools(mirror, grey, grid, rotation)

    val practice = practiceSeconds > 0
    var remaining by rememberSaveable { mutableIntStateOf(practiceSeconds) }
    var paused by rememberSaveable { mutableStateOf(false) }
    var finished by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(pager.settledPage) {
        Session.viewerPageChanged(pager.settledPage)
    }
    if (practice) {
        // Keyed on the settled page so a page change (timed or swiped)
        // restarts the clock, but only after the swipe has finished.
        var lastPage by rememberSaveable { mutableIntStateOf(pager.settledPage) }
        LaunchedEffect(pager.settledPage) {
            if (pager.settledPage != lastPage) {
                lastPage = pager.settledPage
                remaining = practiceSeconds
            }
        }
        LaunchedEffect(pager.settledPage, paused, finished) {
            while (!paused && !finished) {
                delay(1000)
                remaining--
                if (remaining <= 0) {
                    if (pager.settledPage < images.size - 1) {
                        pager.animateScrollToPage(pager.settledPage + 1)
                    } else {
                        finished = true
                    }
                    break
                }
            }
        }
    }

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
                tools = tools,
                onZoomChanged = { if (page == pager.currentPage) zoomed = it },
                onTap = { chrome = !chrome },
            )
        }

        val current = images[pager.currentPage]

        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                    .statusBarsPadding().padding(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                if (practice) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            formatClock(remaining.coerceAtLeast(0)),
                            color = Color.White,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                        )
                        IconButton(onClick = { paused = !paused }) {
                            Icon(
                                if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                                if (paused) "Resume" else "Pause",
                                tint = Color.White,
                            )
                        }
                        IconButton(
                            enabled = pager.currentPage < images.size - 1,
                            onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                        ) { Icon(Icons.Outlined.SkipNext, "Next image", tint = Color.White) }
                    }
                    LinearProgressIndicator(
                        progress = { remaining.coerceAtLeast(0).toFloat() / practiceSeconds },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
        }

        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                    .padding(bottom = 12.dp, top = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToolToggle(mirror, Icons.Outlined.Flip, "Mirror") { mirror = !mirror }
                    ToolToggle(grey, Icons.Outlined.FilterBAndW, "Greyscale") { grey = !grey }
                    ToolToggle(grid != 0, Icons.Outlined.GridOn, "Grid") {
                        grid = when (grid) { 0 -> 3; 3 -> 4; else -> 0 }
                    }
                    ToolToggle(rotation != 0, Icons.Outlined.RotateRight, "Rotate") { rotation = (rotation + 1) % 4 }
                    if (grid != 0) {
                        Text(
                            if (grid == 3) "Thirds" else "4×4",
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
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
                            val ok = Refboard.downloader.saveOne(current.url)
                            Session.toast(if (ok) "Saved to ${Refboard.downloader.savedLabel}/Saved" else "Download failed")
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

        if (finished) {
            Column(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Session complete", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${images.size} images · ${formatClock(practiceSeconds)} each",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = onBack) { Text("Done") }
            }
        }
    }

    pickFor?.let { img ->
        BoardPickerSheet(
            title = "Pin image to…",
            onDismiss = { pickFor = null },
            onPicked = { board ->
                pickFor = null
                val added = Refboard.boards.addPins(
                    board.id, listOf(Pin(img.url, img.galleryUrl, img.title, img.tags, ratio = ImageRatios.get(Thumbs.small(img.url)))),
                )
                Session.toast(if (added > 0) "Pinned to ${board.name}" else "Already on ${board.name}")
            },
        )
    }
}

@Composable
private fun ToolToggle(
    checked: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    FilledTonalIconToggleButton(checked = checked, onCheckedChange = { onClick() }) {
        Icon(icon, label)
    }
}

fun formatClock(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

/**
 * Pinch to zoom, drag to pan while zoomed, double-tap to toggle 2.5x.
 * One-finger swipes pass through to the pager when not zoomed.
 */
@Composable
private fun ZoomableImage(
    url: String,
    tools: ViewTools,
    onZoomChanged: (Boolean) -> Unit,
    onTap: () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var intrinsic by remember(url) { mutableStateOf(Size.Unspecified) }

    fun setScale(s: Float) {
        val wasZoomed = scale > 1f
        scale = s.coerceIn(1f, 5f)
        if (scale == 1f) offset = Offset.Zero
        if (wasZoomed != (scale > 1f)) onZoomChanged(scale > 1f)
    }

    LaunchedEffect(tools.rotation) { setScale(1f) }

    // A sideways image has to shrink to fit the screen.
    val imageKnown = intrinsic.isSpecified && intrinsic.width > 0f && intrinsic.height > 0f &&
        box.width > 0 && box.height > 0
    val turnFit = if (tools.rotation % 2 == 1 && imageKnown) {
        val upright = min(box.width / intrinsic.width, box.height / intrinsic.height)
        val sideways = min(box.width / intrinsic.height, box.height / intrinsic.width)
        sideways / upright
    } else 1f
    val greyFilter = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }

    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { box = it }
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
            colorFilter = if (tools.grey) greyFilter else null,
            onSuccess = { intrinsic = it.painter.intrinsicSize },
            loading = { CircularProgressIndicator(color = Color.White.copy(alpha = 0.6f)) },
            error = { Text("Couldn't load image", color = Color.White.copy(alpha = 0.7f)) },
            modifier = Modifier.fillMaxSize()
                .graphicsLayer(
                    scaleX = scale * turnFit * (if (tools.mirror) -1f else 1f),
                    scaleY = scale * turnFit,
                    rotationZ = 90f * tools.rotation,
                    translationX = offset.x,
                    translationY = offset.y,
                )
                .drawWithContent {
                    drawContent()
                    if (tools.grid == 0 || !imageKnown) return@drawWithContent
                    // Grid over the fitted image, not the whole screen.
                    val fit = min(size.width / intrinsic.width, size.height / intrinsic.height)
                    val w = intrinsic.width * fit
                    val h = intrinsic.height * fit
                    val left = (size.width - w) / 2f
                    val top = (size.height - h) / 2f
                    val thin = 1.dp.toPx() / (scale * turnFit)
                    for (i in 1 until tools.grid) {
                        val x = left + w * i / tools.grid
                        val y = top + h * i / tools.grid
                        for ((color, width) in listOf(Color.Black.copy(alpha = 0.5f) to thin * 3f, Color.White.copy(alpha = 0.8f) to thin)) {
                            drawLine(color, Offset(x, top), Offset(x, top + h), width)
                            drawLine(color, Offset(left, y), Offset(left + w, y), width)
                        }
                    }
                },
        )
    }
}
