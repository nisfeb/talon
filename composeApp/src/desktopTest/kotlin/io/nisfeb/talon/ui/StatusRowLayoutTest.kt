package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.data.ContactEntity
import io.nisfeb.talon.ui.screens.StatusRow
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "~nomryg-nilref (Jack Fox)": the nickname took the row's width and the
 * @p beside it was a column one letter wide, spelled down the row.
 */
@OptIn(ExperimentalTestApi::class)
class StatusRowLayoutTest {
    private fun row(nickname: String, ship: String, block: androidx.compose.ui.test.ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                Box(Modifier.width(380.dp)) {
                    StatusRow(ContactEntity(ship, nickname, null, null, status = "confused", statusUpdatedMs = 1L)) {}
                }
            }
        }
        block()
    }

    @Test
    fun `a nickname that already says the @p does not say it twice`() = row("~nomryg-nilref (Jack Fox)", "~nomryg-nilref") {
        assertEquals(1, onAllNodesWithText("~nomryg-nilref", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `a long nickname leaves the @p on one line`() = row("Somebody with a very long nickname indeed", "~sampel-palnet") {
        val b = onNodeWithText("~sampel-palnet").fetchSemanticsNode().boundsInRoot
        assertTrue(b.width > b.height, "one line, not a column: $b")
    }
}
