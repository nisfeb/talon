package io.nisfeb.talon.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The count the sections drawer's swipe goes by: every back handler that is on, and only while it is. */
@OptIn(ExperimentalTestApi::class)
class BackDepthTest {
    @Test
    fun `each enabled handler counts while it is on, and none once gone`() = runComposeUiTest {
        var a by mutableStateOf(false)
        var b by mutableStateOf(false)
        var shown by mutableStateOf(true)
        val base = BackDepth.depth
        setContent {
            if (shown) {
                BackDepth.Count(a)
                BackDepth.Count(b)
            }
        }
        waitForIdle()
        assertEquals(base, BackDepth.depth, "a handler that is off is not counted")
        a = true
        waitForIdle()
        assertEquals(base + 1, BackDepth.depth)
        b = true
        waitForIdle()
        assertEquals(base + 2, BackDepth.depth)
        a = false
        waitForIdle()
        assertEquals(base + 1, BackDepth.depth)
        shown = false
        waitForIdle()
        assertEquals(base, BackDepth.depth, "a screen that leaves takes its handlers' count with it")
    }

}
