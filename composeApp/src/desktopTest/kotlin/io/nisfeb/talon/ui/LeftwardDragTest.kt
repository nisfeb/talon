package io.nisfeb.talon.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A message's reply swipe takes drags to the left and nothing else, so a
 * swipe right on a message reaches the screen around it: on iOS that is
 * the swipe back. The row used to claim every sideways drag, and a swipe
 * back that began on a message went nowhere.
 */
@OptIn(ExperimentalTestApi::class)
class LeftwardDragTest {
    private var row = 0f
    private var ends = 0
    private var screen = 0f
    private var slop = 0f

    private fun ComposeUiTest.message() = setContent {
        slop = androidx.compose.ui.platform.LocalViewConfiguration.current.touchSlop
        Box(Modifier.size(400.dp).pointerInput(Unit) { detectHorizontalDragGestures { _, dx -> screen += dx } }) {
            Box(
                Modifier.testTag("row").fillMaxWidth().height(80.dp)
                    .leftwardDrag(Unit, onDrag = { row += it }, onEnd = { ends++ }, onCancel = {}),
            )
        }
    }

    @Test
    fun `a swipe left is the row's`() = runComposeUiTest {
        message()
        var travel = 0f
        onNodeWithTag("row").performTouchInput {
            travel = centerLeft.x - centerRight.x
            swipeLeft()
        }
        waitForIdle()
        // All of the way past the slop, as the reply swipe always had it.
        assertEquals(travel + slop, row, 1f, "the row followed the finger")
        assertEquals(1, ends)
        assertEquals(0f, screen, "and the screen around it heard nothing")
    }

    @Test
    fun `a swipe right goes to the screen around the row`() = runComposeUiTest {
        message()
        onNodeWithTag("row").performTouchInput { swipeRight() }
        waitForIdle()
        assertEquals(0f, row, "the row did not move")
        assertEquals(0, ends)
        assertTrue(screen > 100f, "the screen had it: $screen")
    }

    @Test
    fun `a swipe that starts down the list stays the list's, though it turns left`() = runComposeUiTest {
        message()
        onNodeWithTag("row").performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(0f, 30f))
            moveBy(androidx.compose.ui.geometry.Offset(-120f, 0f))
            moveBy(androidx.compose.ui.geometry.Offset(-120f, 0f))
            up()
        }
        waitForIdle()
        assertEquals(0f, row)
        assertEquals(0, ends)
    }

    @Test
    fun `a swipe mostly left is the row's, though it drifts down`() = runComposeUiTest {
        message()
        onNodeWithTag("row").performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(-100f, 30f))
            moveBy(androidx.compose.ui.geometry.Offset(-100f, 10f))
            up()
        }
        waitForIdle()
        assertTrue(row < -100f, "the row took it: $row")
        assertEquals(1, ends)
    }

    @Test
    fun `a swipe up is neither's`() = runComposeUiTest {
        message()
        onNodeWithTag("row").performTouchInput { swipeUp() }
        waitForIdle()
        assertEquals(0f, row)
        assertEquals(0, ends)
    }
}
