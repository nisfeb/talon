package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pin the image-viewer swipe decision. Two contracts:
 *   - Scale > 1f → swipe disabled (transformable state pans the
 *     zoomed image instead).
 *   - At scale == 1f, drag past threshold → navigate; below
 *     threshold → ignore.
 *
 * Direction matches `detectHorizontalDragGestures`'s accumulator
 * convention: positive drag = finger moved right = previous image;
 * negative = next.
 */
class ImageViewerSwipeTest {

    private val threshold = 60f

    @Test
    fun `tiny drag does nothing`() {
        assertEquals(SwipeAction.None, decideSwipeAction(5f, threshold, scale = 1f))
        assertEquals(SwipeAction.None, decideSwipeAction(-5f, threshold, scale = 1f))
    }

    @Test
    fun `drag right past threshold navigates to previous`() {
        assertEquals(
            SwipeAction.Previous,
            decideSwipeAction(threshold + 1f, threshold, scale = 1f),
        )
    }

    @Test
    fun `drag left past threshold navigates to next`() {
        assertEquals(
            SwipeAction.Next,
            decideSwipeAction(-(threshold + 1f), threshold, scale = 1f),
        )
    }

    @Test
    fun `drag exactly at threshold does NOT navigate`() {
        // Strict greater-than. Equal-to is no-op so a "right at
        // threshold" twitch doesn't navigate by accident.
        assertEquals(SwipeAction.None, decideSwipeAction(threshold, threshold, scale = 1f))
        assertEquals(SwipeAction.None, decideSwipeAction(-threshold, threshold, scale = 1f))
    }

    @Test
    fun `scale above 1f disables navigation regardless of drag`() {
        // User pinch-zoomed; horizontal drag is now panning, not
        // navigating. The gesture detector itself skips at the
        // pointerInput key level, but if anything calls into the
        // pure decider with scale > 1f it must still say None.
        assertEquals(SwipeAction.None, decideSwipeAction(500f, threshold, scale = 1.5f))
        assertEquals(SwipeAction.None, decideSwipeAction(-500f, threshold, scale = 2.5f))
        assertEquals(SwipeAction.None, decideSwipeAction(0f, threshold, scale = 6f))
    }

    @Test
    fun `scale exactly at 1f keeps navigation enabled`() {
        // Boundary: scale == 1f → still un-zoomed → navigation works.
        assertEquals(
            SwipeAction.Previous,
            decideSwipeAction(threshold + 1f, threshold, scale = 1f),
        )
    }

    @Test
    fun `scale just above 1f disables navigation`() {
        assertEquals(
            SwipeAction.None,
            decideSwipeAction(threshold + 100f, threshold, scale = 1.0001f),
        )
    }

    @Test
    fun `zero drag is a no-op even with low threshold`() {
        assertEquals(SwipeAction.None, decideSwipeAction(0f, 10f, scale = 1f))
    }
}
