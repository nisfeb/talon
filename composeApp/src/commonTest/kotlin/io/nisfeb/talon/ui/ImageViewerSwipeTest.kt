package io.nisfeb.talon.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
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
    fun `scale just above 1f disables navigation`() {
        assertEquals(
            SwipeAction.None,
            decideSwipeAction(threshold + 100f, threshold, scale = 1.0001f),
        )
    }


    // Zoom and pan (2026-10-09: "unusable" on a phone).
    private val view = Size(1000f, 1000f)

    @Test
    fun `a pinch keeps the point under the fingers under them`() {
        val fingers = Offset(300f, 400f)
        val (s, o) = zoomStep(1f, Offset.Zero, fingers, Offset.Zero, 2f, view)
        assertEquals(2f, s)
        assertEquals(Offset(-300f, -400f), o)
        assertEquals(fingers, (fingers - o) / s, "the same point of the picture")
        val (s2, o2) = zoomStep(s, o, Offset(700f, 100f), Offset.Zero, 1.5f, view)
        assertEquals((Offset(700f, 100f) - o) / s, (Offset(700f, 100f) - o2) / s2)
    }

    @Test
    fun `a pan moves the picture as far as the finger, at any zoom`() {
        assertEquals(3f to Offset(-470f, -500f), zoomStep(3f, Offset(-500f, -500f), Offset(500f, 500f), Offset(30f, 0f), 1f, view))
    }

    @Test
    fun `a flick stops with the picture's edge at the screen's edge`() {
        assertEquals(Offset.Zero, zoomStep(3f, Offset(-500f, -500f), Offset(500f, 500f), Offset(5000f, 5000f), 1f, view).second)
        assertEquals(Offset(-2000f, -2000f), zoomStep(3f, Offset(-500f, -500f), Offset(500f, 500f), Offset(-5000f, -5000f), 1f, view).second)
    }

    @Test
    fun `zooming back out puts it back where it started, and no further`() {
        assertEquals(1f to Offset.Zero, zoomStep(2f, Offset(-300f, -400f), Offset(10f, 900f), Offset(50f, 50f), 0.25f, view))
        assertEquals(6f, zoomStep(5f, Offset.Zero, Offset.Zero, Offset.Zero, 4f, view).first)
    }

    @Test
    fun `a letterboxed picture stays centred on its narrow axis, its bars never pulled in`() {
        // A landscape photo on a portrait phone: 1000 x 500 in a 1000 x 2000 screen.
        val phone = Size(1000f, 2000f)
        val photo = fittedRect(Size(4000f, 2000f), phone)
        assertEquals(Rect(Offset(0f, 750f), Size(1000f, 500f)), photo)
        val (s, o) = zoomStep(1f, Offset.Zero, Offset(500f, 1000f), Offset(0f, 300f), 2f, phone, photo)
        assertEquals(2f, s)
        assertEquals(500f, o.y + photo.top * s, "1000 tall in 2000: centred, the drag down ignored")
        // At 6x it is 3000 tall: its top edge stops at the screen's.
        val (s6, o6) = zoomStep(1f, Offset.Zero, Offset(500f, 1000f), Offset(0f, 5000f), 6f, phone, photo)
        assertEquals(0f, o6.y + photo.top * s6)
        assertEquals(1f to Offset.Zero, zoomStep(s6, o6, Offset(500f, 1000f), Offset.Zero, 1f / 6f, phone, photo), "and at 1x back where it began")
    }

    @Test
    fun `a picture not yet loaded counts as the whole screen`() {
        assertEquals(Rect(Offset.Zero, view), fittedRect(Size.Unspecified, view))
        assertEquals(Rect(Offset(250f, 0f), Size(500f, 1000f)), fittedRect(Size(100f, 200f), view))
    }
}
