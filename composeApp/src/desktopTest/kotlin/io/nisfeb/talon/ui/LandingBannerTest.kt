package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.nisfeb.talon.ui.theme.TalonTheme
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A fresh comet getting ready: the step and how long it has taken, the
 * ship's own last line so the wait is visibly its work, a slow wait said
 * to be slow and not failed, and the terminal or hiding within reach.
 */
@OptIn(ExperimentalTestApi::class)
class LandingBannerTest {
    private val did = CopyOnWriteArrayList<String>()

    private fun banner(progress: LandingProgress, line: String? = null, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                LandingBanner(progress, terminalLine = line, onOpenTerminal = { did += "terminal" }, onDismiss = { did += "hide" })
            }
        }
        block()
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the step and its time show, with the ship's last line`() = banner(LandingProgress("Joining Nisfeb Software", 125), line = "~zod:dojo> |install") {
        assertTrue(shows("Getting your ship ready") && shows("Joining Nisfeb Software  ·  2m 5s"))
        assertTrue(shows("~zod:dojo> |install"))
        onNodeWithText("Terminal").performClick()
        onNodeWithText("Hide").performClick()
        assertEquals(listOf("terminal", "hide"), did.toList())
    }

    @Test
    fun `a slow landing says it is slow, not broken`() = banner(LandingProgress("Waiting for the ship to confirm the group", 700, slow = true)) {
        assertTrue(shows("Still getting your ship ready") && shows("Nothing has gone wrong"))
    }

    @Test
    fun `a short wait counts in seconds`() = banner(LandingProgress("Connecting to your ship", 9)) {
        assertTrue(shows("Connecting to your ship  ·  9s"))
    }
}
