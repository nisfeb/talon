package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Leaving a group from its long-press sheet waits to hear it went. */
@OptIn(ExperimentalTestApi::class)
class FolderAssignmentSheetTest {
    private var dismissed = 0

    private fun sheet(leave: suspend () -> Unit, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                FolderAssignmentSheet(
                    conversationLabel = "The Crew", folders = emptyList(), selectedFolderIds = emptySet(),
                    onToggle = { _, _ -> }, onCreateNew = {}, onDismiss = { dismissed++ },
                    onLeaveGroup = leave,
                )
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Leave group").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Leave group").performClick()
        onNodeWithText("Leave").performClick()
        block()
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a leave that went closes the sheet`() = sheet(leave = {}) {
        waitUntil(timeoutMillis = 5_000) { dismissed == 1 }
    }

    @Test
    fun `a refused leave says why and keeps the sheet`() = sheet(leave = { error("not a member") }) {
        waitUntil(timeoutMillis = 5_000) { shows("not a member") }
        assertTrue(shows("Leave The Crew?"))
        assertEquals(0, dismissed)
    }
}
