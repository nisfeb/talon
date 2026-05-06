package io.nisfeb.talon.ui

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
