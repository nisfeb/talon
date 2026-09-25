package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.nisfeb.talon.ui.screens.ShipSwitcherDrawer
import io.nisfeb.talon.ui.theme.TalonTheme
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The drawer of signed-in ships: each by the name it goes by, picked
 * with a tap, another added; signing out keeps what was cached, and
 * deleting it asks first, saying what goes.
 */
@OptIn(ExperimentalTestApi::class)
class ShipSwitcherDrawerTest {
    private val did = CopyOnWriteArrayList<String>()

    private fun drawer(wired: Boolean = true, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                ShipSwitcherDrawer(
                    ships = listOf("~zod", "~mitlyn-ditrel"), activeShip = "~zod", nicknames = mapOf("~mitlyn-ditrel" to "Work"),
                    onPick = { did += "pick $it" }, onAdd = { did += "add" },
                    onSignOut = if (wired) ({ did += "out $it" }) else null,
                    onForget = if (wired) ({ did += "forget $it" }) else null,
                )
            }
        }
        waitForIdle()
        block()
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a ship is picked by the name it goes by, and another is added`() = drawer {
        assertTrue(shows("Work") && shows("~mitlyn-ditrel"), "a nickname, with the ship under it")
        onNodeWithText("Work").performClick()
        onNodeWithText("Add ship").performClick()
        waitForIdle()
        assertEquals(listOf("pick ~mitlyn-ditrel", "add"), did.toList())
    }

    @Test
    fun `signing out keeps the data, and deleting it asks first`() = drawer {
        onNodeWithContentDescription("What to do with ~zod").performClick()
        onNodeWithText("Sign out").performClick()
        waitForIdle()
        onNodeWithContentDescription("What to do with ~zod").performClick()
        onNodeWithText("Sign out and delete data").performClick()
        waitForIdle()
        assertTrue(shows("Delete ~zod's data?") && shows("Nothing on the ship itself is touched"))
        onNodeWithText("Cancel").performClick()
        waitForIdle()
        assertEquals(listOf("out ~zod"), did.toList(), "cancelled deletes nothing")
        onNodeWithContentDescription("What to do with ~zod").performClick()
        onNodeWithText("Sign out and delete data").performClick()
        onNodeWithText("Delete").performClick()
        waitForIdle()
        assertEquals(listOf("out ~zod", "forget ~zod"), did.toList())
    }

    @Test
    fun `where signing out is not wired there is no menu`() = drawer(wired = false) {
        assertTrue(onAllNodesWithContentDescription("What to do with ~zod").fetchSemanticsNodes().isEmpty())
    }
}
