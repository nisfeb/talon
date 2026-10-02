package io.nisfeb.talon.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HoverTipTest {
    // An icon alone said nothing on desktop until it was clicked.
    @Test
    fun `an icon button says what it does on hover, and still takes a click`() = runComposeUiTest {
        var clicks = 0
        setContent { IconButton(onClick = { clicks++ }, tip = "Remove the item") { Icon(Icons.Filled.Close, contentDescription = "Remove the item") } }
        assertTrue(onAllNodesWithText("Remove the item").fetchSemanticsNodes().isEmpty(), "no tip until hovered")
        onNodeWithContentDescription("Remove the item").performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(800)
        waitForIdle()
        assertTrue(onAllNodesWithText("Remove the item").fetchSemanticsNodes().isNotEmpty(), "the tip shows")
        onNodeWithContentDescription("Remove the item").performClick()
        assertEquals(1, clicks)
    }
}
