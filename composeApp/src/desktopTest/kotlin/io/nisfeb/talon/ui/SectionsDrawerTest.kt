package io.nisfeb.talon.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
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
 * The phone's drawer: the sections in the order and with the hiding the
 * rail uses, only those this host can open, the one showing marked, and
 * the menu's own editor at the foot.
 */
@OptIn(ExperimentalTestApi::class)
class SectionsDrawerTest {
    private val went = CopyOnWriteArrayList<String>()

    @Test
    fun `sections come in the saved order, hidden and unreachable ones left out`() = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                SectionsDrawer(
                    order = listOf(RailItem.Mail, RailItem.Chats, RailItem.Invites, RailItem.Calendar, RailItem.Settings),
                    visibility = mapOf(RailItem.Invites to false),
                    active = RailItem.Chats,
                    onSection = { went += railLabel(it) },
                    canOpen = { it != RailItem.Calendar },
                    onEditMenu = { went += "edit" },
                )
            }
        }
        val labels = listOf("Mail", "Chats", "Settings")
        val tops = labels.map { onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
        assertEquals(tops.sorted(), tops, "in the saved order")
        assertTrue(onAllNodesWithText("Invites").fetchSemanticsNodes().isEmpty(), "switched off")
        assertTrue(onAllNodesWithText("Calendar").fetchSemanticsNodes().isEmpty(), "this host cannot open it")
        onNodeWithText("Chats").assertIsSelected()
        onNodeWithText("Mail").performClick()
        onNodeWithText("Edit menu").performClick()
        assertEquals(listOf("Mail", "edit"), went.toList())
    }

    // The assistant was the letter "A": narrower than an icon, it pulled
    // its label out of line with every other one in the menu.
    @Test
    fun `every section's label lines up, the assistant's too`() = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                SectionsDrawer(
                    order = listOf(RailItem.Chats, RailItem.Assistant, RailItem.Settings),
                    visibility = emptyMap(),
                    active = RailItem.Chats,
                    onSection = {},
                    canOpen = { true },
                    onEditMenu = null,
                )
            }
        }
        val lefts = listOf("Chats", "Assistant", "Settings").map { onNodeWithText(it).fetchSemanticsNode().boundsInRoot.left }
        assertEquals(1, lefts.distinct().size, "label lefts: $lefts")
        assertTrue(onAllNodesWithText("A").fetchSemanticsNodes().isEmpty(), "no letter standing in for an icon")
    }
}
