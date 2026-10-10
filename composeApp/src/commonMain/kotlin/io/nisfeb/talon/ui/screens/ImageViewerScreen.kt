package io.nisfeb.talon.ui.screens

import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.nisfeb.talon.ui.LocalImageDownloader
import io.nisfeb.talon.ui.SaveResult
import kotlinx.coroutines.launch
import io.nisfeb.talon.ui.icons.TalonIcons

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
    // The picture's top-left corner on screen; it is drawn from there at [scale].
    var offset by remember(url) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var viewSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    var pictureSize by remember(url) { mutableStateOf(androidx.compose.ui.geometry.Size.Unspecified) }
    fun step(centroid: androidx.compose.ui.geometry.Offset, pan: androidx.compose.ui.geometry.Offset, zoom: Float) {
        val (s, o) = io.nisfeb.talon.ui.zoomStep(scale, offset, centroid, pan, zoom, viewSize, io.nisfeb.talon.ui.fittedRect(pictureSize, viewSize))
        scale = s
        offset = o
    }
    // A rotation, a resize or the picture loading moves the bounds; a zoomed
    // picture is put back inside them at once, not on the next touch.
    LaunchedEffect(viewSize, pictureSize) {
        if (scale > 1f) step(androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.geometry.Offset.Zero, 1f)
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
                    onDoubleTap = { at ->
                        // In about the tapped point, or back out.
                        step(at, androidx.compose.ui.geometry.Offset.Zero, if (scale > 1.5f) 1f / scale else 2.5f / scale)
                    },
                )
            }
            // Swipe-to-navigate. Only while un-zoomed: once scale > 1f
            // the picture's own gesture handler takes a drag as a pan
            // and consumes it, so this never starts, and
            // decideSwipeAction refuses a zoomed one regardless.
            // Threshold (60.dp converted to px) is the same
            // ballpark as the system's edge-back gesture, tuned by
            // feel — short enough that a quick flick goes through,
            // long enough that an accidental drag while reading
            // doesn't.
            .pointerInput(urls, index) {
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
        // Decode the original, not a screen-sized copy: with pinch zoom
        // up to 6x a screen-sized decode is what "too compressed" looked
        // like. High filter quality so the downscale is not aliased.
        val context = coil3.compose.LocalPlatformContext.current
        val request = androidx.compose.runtime.remember(url) {
            coil3.request.ImageRequest.Builder(context)
                .data(url)
                .size(coil3.size.Size.ORIGINAL)
                .build()
        }
        // The gestures on the frame, which does not move, and the zoom on
        // the picture inside it. They were on the zoomed layer itself, so
        // a finger's travel was divided by the zoom (a 30 px drag panned
        // 10 at 3x) and the touch area moved with the picture. One finger
        // at 1x is left alone, for the swipe between pictures.
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { viewSize = androidx.compose.ui.geometry.Size(it.width.toFloat(), it.height.toFloat()) }
                // A mouse and a trackpad, which press nothing: Ctrl+wheel and a
                // pinch zoom about the pointer, as Compose's transformable did
                // before 1.8.15; a plain wheel or two-finger scroll pans.
                .pointerInput(url) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            when (event.type) {
                                androidx.compose.ui.input.pointer.PointerEventType.Scroll -> {
                                    val d = change.scrollDelta
                                    if (event.keyboardModifiers.isCtrlPressed) step(change.position, androidx.compose.ui.geometry.Offset.Zero, kotlin.math.exp(-d.y * WHEEL_ZOOM))
                                    else if (scale > 1f) step(change.position, -d * WHEEL_PAN_PX, 1f)
                                    else continue
                                }
                                androidx.compose.ui.input.pointer.PointerEventType.ScaleChange -> {
                                    val k = change.historical.lastOrNull()?.scaleFactor ?: continue
                                    step(change.position, androidx.compose.ui.geometry.Offset.Zero, k)
                                }
                                else -> continue
                            }
                            change.consume()
                        }
                    }
                }
                .pointerInput(url) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        // Nothing moves until the fingers pass the touch slop, so a
                        // double tap with a wobble still zooms back out; then the
                        // travel so far is applied at once and the picture stays
                        // under the finger.
                        var zoomed = 1f
                        var panned = androidx.compose.ui.geometry.Offset.Zero
                        var moving = false
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } < 2 && scale <= 1f) continue
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = false)
                            if (!centroid.isSpecified) continue
                            if (moving) step(centroid, pan, zoom)
                            else {
                                zoomed *= zoom
                                panned += pan
                                val slop = viewConfiguration.touchSlop
                                moving = kotlin.math.abs(1f - zoomed) * event.calculateCentroidSize(useCurrent = false) > slop || panned.getDistance() > slop
                                if (moving) step(centroid, panned, zoomed)
                            }
                            if (moving) event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                },
        ) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                onSuccess = { pictureSize = it.painter.intrinsicSize },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f),
                    )
                    .testTag("viewer-image"),
            )
        }

        // Prev / next buttons. Hidden when only a single image is
        // open. Disabled-but-visible at the ends so the user gets
        // feedback that they're at the boundary.
        if (multi) {
            LightboxIconButton(
                onClick = ::goPrev,
                enabled = hasPrev,
                contentDescription = "Previous image",
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
            }
            LightboxIconButton(
                onClick = ::goNext,
                enabled = hasNext,
                contentDescription = "Next image",
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
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
            LightboxIconButton(
                onClick = onClose,
                contentDescription = "Close",
            ) {
                Icon(Icons.Filled.Close, contentDescription = null)
            }
            if (multi) {
                Text(
                    "${index + 1} / ${urls.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (downloader !== io.nisfeb.talon.ui.NoopImageDownloader) {
                LightboxIconButton(
                    onClick = {
                        if (saving) return@LightboxIconButton
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
                    contentDescription = "Download",
                ) {
                    Icon(TalonIcons.Download, contentDescription = null)
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

/**
 * Lightbox icon button with a semi-transparent circular halo on
 * hover / press. Without this the bare-glyph IconButton against the
 * black backdrop has almost no clickable affordance — desktop users
 * weren't sure the icons were buttons. Hover state isn't visible on
 * Android (no pointer) but the press state still benefits.
 *
 * Container alpha values match Material 3's state-layer convention
 * (~12% hover, ~20% press) but tinted white so they read on the
 * black backdrop instead of disappearing.
 */
@Composable
private fun LightboxIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val container = when {
        !enabled -> Color.Transparent
        pressed -> Color.White.copy(alpha = 0.20f)
        hovered -> Color.White.copy(alpha = 0.12f)
        else -> Color.Transparent
    }
    IconButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        modifier = modifier.size(48.dp),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = container,
            contentColor = Color.White,
            disabledContentColor = Color.White.copy(alpha = 0.3f),
        ),
    ) {
        // Wrap with semantics so screen readers pick up the
        // description even though the inner Icon passes
        // contentDescription = null.
        Box(
            modifier = Modifier.semantics { this.contentDescription = contentDescription },
        ) {
            content()
        }
    }
}

/** How far one wheel notch zooms (e^0.2, about 22%), and how far one pans. */
private const val WHEEL_ZOOM = 0.2f
private const val WHEEL_PAN_PX = 48f
