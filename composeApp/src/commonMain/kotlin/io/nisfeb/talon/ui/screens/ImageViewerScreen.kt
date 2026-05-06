package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.nisfeb.talon.ui.LocalImageDownloader
import io.nisfeb.talon.ui.SaveResult
import kotlinx.coroutines.launch

/**
 * State holder for the multi-image viewer mode (the photo / gif
 * drilldown in GroupInfoPane). Single-image callers don't need this —
 * they call [ImageViewerScreen] directly with `urls = listOf(theUrl)`.
 */
data class ViewerImageList(val urls: List<String>, val initialIndex: Int = 0)

/**
 * Fullscreen image viewer with pinch-to-zoom + pan, plus prev/next
 * navigation when called with multiple [urls]. Arrow keys (Left /
 * Right) and the on-screen prev/next buttons step between images;
 * the buttons hide when [urls] has a single entry.
 *
 * Single-image callers (chat row tap, notebook post, gallery post)
 * pass `urls = listOf(theUrl)` and skip [initialIndex].
 */
@Composable
fun ImageViewerScreen(
    urls: List<String>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    initialIndex: Int = 0,
) {
    if (urls.isEmpty()) {
        // Defensive: an empty list with no URL would render a black
        // void with no way out. Treat as "close immediately".
        LaunchedEffect(Unit) { onClose() }
        return
    }
    var index by remember(urls) {
        mutableStateOf(initialIndex.coerceIn(0, urls.size - 1))
    }
    val url = urls[index]
    // Reset zoom + pan whenever we step to a different image so the
    // next image starts unzoomed regardless of how the previous was
    // viewed.
    var scale by remember(url) { mutableStateOf(1f) }
    var offsetX by remember(url) { mutableStateOf(0f) }
    var offsetY by remember(url) { mutableStateOf(0f) }

    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        if (scale > 1f) {
            offsetX += pan.x
            offsetY += pan.y
        } else {
            // Snap back to center once the user pinches below 1x.
            offsetX = 0f
            offsetY = 0f
        }
    }

    val downloader = LocalImageDownloader.current
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var saving by remember(url) { mutableStateOf(false) }

    val multi = urls.size > 1
    val hasPrev = multi && index > 0
    val hasNext = multi && index < urls.size - 1
    fun goPrev() { if (hasPrev) index -= 1 }
    fun goNext() { if (hasNext) index += 1 }

    // Focus requester so the Box receives key events on desktop. The
    // requestFocus() in LaunchedEffect runs after first composition.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (ev.key) {
                    Key.DirectionLeft -> { goPrev(); true }
                    Key.DirectionRight -> { goNext(); true }
                    Key.Escape -> { onClose(); true }
                    else -> false
                }
            }
            .pointerInput(url) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1.5f) {
                            scale = 1f; offsetX = 0f; offsetY = 0f
                        } else {
                            scale = 2.5f
                        }
                    },
                )
            }
            // Swipe-to-navigate. Only active when un-zoomed — once
            // scale > 1f the user's horizontal drag is panning within
            // the image, so we hand off to the transformable state.
            // Re-keyed on (urls.size, scale) so that zoom-in then
            // zoom-out flips swipe back on without restarting the
            // viewer. Threshold (60.dp converted to px) is the same
            // ballpark as the system's edge-back gesture, tuned by
            // feel — short enough that a quick flick goes through,
            // long enough that an accidental drag while reading
            // doesn't.
            .pointerInput(urls.size, scale) {
                if (scale > 1f) return@pointerInput
                val thresholdPx = 60.dp.toPx()
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onDragEnd = {
                        // Pure decision in `decideSwipeAction`
                        // (ImageViewerSwipe.kt). Logic-tested in
                        // commonTest; we just route the result here.
                        when (
                            io.nisfeb.talon.ui.decideSwipeAction(
                                totalDrag = totalDrag,
                                thresholdPx = thresholdPx,
                                scale = scale,
                            )
                        ) {
                            io.nisfeb.talon.ui.SwipeAction.Previous -> goPrev()
                            io.nisfeb.talon.ui.SwipeAction.Next -> goNext()
                            io.nisfeb.talon.ui.SwipeAction.None -> Unit
                        }
                        totalDrag = 0f
                    },
                    onDragCancel = { totalDrag = 0f },
                    onHorizontalDrag = { _, dragAmount ->
                        totalDrag += dragAmount
                    },
                )
            },
    ) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                )
                .transformable(state = transform),
        )

        // Prev / next buttons. Hidden when only a single image is
        // open. Disabled-but-visible at the ends so the user gets
        // feedback that they're at the boundary.
        if (multi) {
            IconButton(
                onClick = ::goPrev,
                enabled = hasPrev,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp)
                    .size(48.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = Color.White,
                    disabledContentColor = Color.White.copy(alpha = 0.3f),
                ),
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous image")
            }
            IconButton(
                onClick = ::goNext,
                enabled = hasNext,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp)
                    .size(48.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = Color.White,
                    disabledContentColor = Color.White.copy(alpha = 0.3f),
                ),
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next image")
            }
        }

        // Top action row: close left, "n of N" centre when multi,
        // download right. Inset-pad first so the icons sit below the
        // status bar / camera notch on Android — without this they
        // overlapped the system clock at the top of the screen.
        // Desktop has no system bar so the inset resolves to zero
        // there and the layout is unchanged.
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onClose,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = Color.White,
                ),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close")
            }
            if (multi) {
                Text(
                    "${index + 1} / ${urls.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (downloader !== io.nisfeb.talon.ui.NoopImageDownloader) {
                IconButton(
                    onClick = {
                        if (saving) return@IconButton
                        saving = true
                        scope.launch {
                            val result = downloader.saveImage(url)
                            val msg = when (result) {
                                is SaveResult.Saved -> "Saved to ${result.location}"
                                is SaveResult.Failed -> result.message
                                SaveResult.Unsupported -> "Image save isn't supported here"
                            }
                            snackbarHost.showSnackbar(msg)
                            saving = false
                        }
                    },
                    enabled = !saving,
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(Icons.Filled.Download, contentDescription = "Download")
                }
            } else {
                // Spacer so the n-of-N text stays centred even when
                // download is hidden — matches the original
                // SpaceBetween-with-three-children layout.
                androidx.compose.foundation.layout.Spacer(Modifier.size(48.dp))
            }
        }

        SnackbarHost(
            hostState = snackbarHost,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) { data ->
            Snackbar(snackbarData = data)
        }
    }
}
