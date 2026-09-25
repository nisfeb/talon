package io.nisfeb.talon.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.nisfeb.talon.data.ContactEntity
import io.nisfeb.talon.data.ReactionEntity
import io.nisfeb.talon.ui.theme.TalonTheme
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Who reacted with what: each reactor by the name they go by, with their
 * @p beneath where the two differ, in name order, the reaction as its
 * glyph; a reactor opens their profile where the screen can show one.
 */
@OptIn(ExperimentalTestApi::class)
class ReactionDetailsSheetTest {
    private val opened = CopyOnWriteArrayList<String>()
    private val contacts = ContactMap(contacts = listOf(ContactEntity("~sampel-palnet", "Sam", null, null)))
    private val reactions = listOf(
        ReactionEntity("~bus", "~bus/1", "~ridlur-figbud", ":+1:"),
        ReactionEntity("~bus", "~bus/1", "~sampel-palnet", "🎉"),
    )

    private fun sheet(profiles: Boolean, block: androidx.compose.ui.test.ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                ReactionDetailsSheet(reactions, contacts, onDismiss = {}, onOpenProfile = if (profiles) { s -> opened += s } else null)
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Reactions").fetchSemanticsNodes().isNotEmpty() }
        block()
    }

    @Test
    fun `each reactor shows by name with the reaction as a glyph, and opens their profile`() = sheet(profiles = true) {
        assertTrue(onAllNodesWithText("Sam").fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodesWithText("~sampel-palnet").fetchSemanticsNodes().isNotEmpty(), "the @p beneath a nickname")
        assertTrue(onAllNodesWithText("👍").fetchSemanticsNodes().isNotEmpty(), "a shortcode shows as its glyph")
        onNodeWithText("Sam").performClick()
        assertEquals(listOf("~sampel-palnet"), opened.toList())
    }

    @Test
    fun `reactors are in name order`() = sheet(profiles = false) {
        val tops = listOf("Sam", "~ridlur-figbud").associateWith { onAllNodesWithText(it)[0].fetchSemanticsNode().boundsInRoot.top }
        assertTrue(tops.getValue("Sam") < tops.getValue("~ridlur-figbud"), tops.toString())
    }

    @Test
    fun `where no profile can be shown, a reactor is not a button`() = sheet(profiles = false) {
        onNodeWithText("Sam").performClick()
        assertTrue(opened.isEmpty())
    }
}
