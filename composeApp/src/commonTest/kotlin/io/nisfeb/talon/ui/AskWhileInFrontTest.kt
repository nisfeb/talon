package io.nisfeb.talon.ui

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Who is recording a line is asked of its host while the app is in
 * front. Focus also comes back when a menu or a dialog closes, and each
 * of those asked the host again: a poke per dismissed menu. Real time:
 * the interval is the point.
 */
class AskWhileInFrontTest {
    @Test
    fun focusComingBackInsideTheIntervalDoesNotAskAgain() = runBlocking {
        val front = MutableStateFlow(true)
        var asks = 0
        val job = launch { askWhileInFront(front, everyMs = 1_000) { asks++ } }
        delay(100)
        assertEquals(1, asks, "in front: asked at once")
        repeat(5) {
            front.value = false
            delay(20)
            front.value = true
            delay(20)
        }
        assertEquals(1, asks, "five menus closed, no new ask")
        delay(1_100)
        assertEquals(2, asks, "the interval past, asked again")
        front.value = false
        delay(1_200)
        front.value = true
        delay(100)
        assertEquals(3, asks, "back in front after the interval asks at once")
        job.cancel()
    }
}
