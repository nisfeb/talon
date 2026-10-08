package io.nisfeb.talon.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import io.nisfeb.talon.ui.screens.ReactionChip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Users asked to see who reacted on hover, like a tooltip, where a
 * right-click list was the only way (sneagan, 2026-10-07). In its own
 * class: a hover in the chat screen's class unsettled a later test there.
 */
@OptIn(ExperimentalTestApi::class)
class ReactionChipTest {
    @Test
    fun `hovering a reaction names who reacted, and a click still reacts`() = runComposeUiTest {
        var clicks = 0
        setContent { ReactionChip(emoji = "🔥", count = 2, mine = true, reactors = "You and ~nec", onClick = { clicks++ }) }
        assertTrue(onAllNodesWithText("You and ~nec").fetchSemanticsNodes().isEmpty(), "no names until hovered")
        onNodeWithText("🔥", substring = true).performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(800)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("You and ~nec").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("🔥", substring = true).performMouseInput { exit() }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("You and ~nec").fetchSemanticsNodes().isEmpty() }
        onNodeWithText("🔥", substring = true).performClick()
        assertEquals(1, clicks)
    }
}
