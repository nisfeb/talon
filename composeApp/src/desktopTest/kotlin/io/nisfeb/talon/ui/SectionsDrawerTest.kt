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
                    order = listOf(RailItem.Mail, RailItem.Chats, RailItem.Watchwords, RailItem.Calendar, RailItem.Settings),
                    visibility = mapOf(RailItem.Watchwords to false),
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
        assertTrue(onAllNodesWithText("Watchwords").fetchSemanticsNodes().isEmpty(), "switched off")
        assertTrue(onAllNodesWithText("Calendar").fetchSemanticsNodes().isEmpty(), "this host cannot open it")
        onNodeWithText("Chats").assertIsSelected()
        onNodeWithText("Mail").performClick()
        onNodeWithText("Edit menu").performClick()
        assertEquals(listOf("Mail", "edit"), went.toList())
    }
}
