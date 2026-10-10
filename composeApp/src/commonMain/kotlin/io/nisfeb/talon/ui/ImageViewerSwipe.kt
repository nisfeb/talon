package io.nisfeb.talon.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.ContentScale

/**
 * Pure decision function for the image viewer's swipe-to-navigate
 * gesture, extracted from [io.nisfeb.talon.ui.screens.ImageViewerScreen]
 * so we have a real test instead of trusting the gesture detector
 * by feel.
 *
 * Two rules:
 *  1. **Scale gate** — if `scale > 1f` (user has pinch-zoomed), the
 *     swipe must NOT navigate. The same horizontal drag is panning
 *     within the zoomed image; the transformable state owns it.
 *  2. **Threshold** — only navigate when the accumulated drag passes
 *     [thresholdPx] in either direction. Short twitches don't count.
 *
 * Direction convention (matches how `detectHorizontalDragGestures`
 * accumulates drag deltas):
 *  - positive total drag → user moved finger to the right →
 *    show the previous image
 *  - negative total drag → user moved finger to the left →
 *    show the next image
 */
enum class SwipeAction { Previous, Next, None }

fun decideSwipeAction(
    totalDrag: Float,
    thresholdPx: Float,
    scale: Float,
): SwipeAction {
    if (scale > 1f) return SwipeAction.None
    return when {
        totalDrag > thresholdPx -> SwipeAction.Previous
        totalDrag < -thresholdPx -> SwipeAction.Next
        else -> SwipeAction.None
    }
}

/**
 * One step of a pinch, a pan or a double tap on a picture drawn at
 * [scale] from [offset] (its top-left corner on screen): zoom by [zoom]
 * about [centroid], so the point under the fingers stays under them,
 * then move by [pan]. The picture itself, [content] within the [view]
 * at 1x, keeps the screen covered on an axis it is wider than, and is
 * centred on one it is narrower than, so neither a flick nor a
 * letterbox's black bars can take the screen. At 1x it sits where it
 * started. It zoomed about the picture's middle and panned without
 * limit, so a corner took a long drag to reach and a flick lost the
 * picture off the screen (2026-10-09).
 */
fun zoomStep(
    scale: Float,
    offset: Offset,
    centroid: Offset,
    pan: Offset,
    zoom: Float,
    view: Size,
    content: Rect = Rect(Offset.Zero, view),
    maxScale: Float = 6f,
): Pair<Float, Offset> {
    val next = (scale * zoom).coerceIn(1f, maxScale)
    // The picture's own point under the fingers, kept under them.
    val moved = centroid - (centroid - offset) / scale * next + pan
    fun axis(o: Float, view: Float, start: Float, size: Float): Float {
        val extent = size * next
        val lead = start * next
        return if (extent <= view) (view - extent) / 2 - lead
        else o.coerceIn(view - extent - lead, 0f - lead)
    }
    return next to Offset(
        axis(moved.x, view.width, content.left, content.width),
        axis(moved.y, view.height, content.top, content.height),
    )
}

/** Where a [picture] sits in the [view] at 1x: fitted and centred, as the viewer draws it; the whole view while its size is not known. */
fun fittedRect(picture: Size, view: Size): Rect {
    if (!picture.isSpecified || picture.width <= 0f || picture.height <= 0f) return Rect(Offset.Zero, view)
    val k = ContentScale.Fit.computeScaleFactor(picture, view)
    val size = Size(picture.width * k.scaleX, picture.height * k.scaleY)
    return Rect(Offset((view.width - size.width) / 2, (view.height - size.height) / 2), size)
}
