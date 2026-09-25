package io.nisfeb.talon.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import io.nisfeb.talon.data.ContactEntity
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A person's card: who they are, and what can be done from it for them or for us. */
@OptIn(ExperimentalTestApi::class)
class ContactProfileSheetTest {
    private val did: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    private val ship = "~mitlyn-ditrel"
    private val person = ContactEntity(ship, "Mittens", "bakes on weekends", null, status = "at the market")

    private fun card(self: Boolean = false, inBook: Boolean = true, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            CompositionLocalProvider(
                LocalClipboardManager provides object : ClipboardManager {
                    override fun getText(): AnnotatedString? = null
                    override fun setText(annotatedString: AnnotatedString) { did += "copied ${annotatedString.text}" }
                },
                io.nisfeb.talon.mail.LocalMailTo provides { to: String -> did += "mail $to" },
            ) {
                TalonTheme(darkTheme = false) {
                    ContactProfileSheet(
                        ship = ship, self = self, contact = person,
                        onMessage = { did += "message" }, onEditSelf = { did += "edit" }, onDismiss = { did += "close" },
                        onAddContact = { did += "add" }, onRemoveContact = { did += "remove" }, isInBook = inBook,
                    )
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) { shows("Mittens") }
        block()
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `someone in the book shows name, status and bio, and can be messaged, mailed or removed`() = card {
        assertTrue(shows("at the market") && shows("bakes on weekends"))
        assertTrue(!shows("Add to contacts") && !shows("Edit profile"))
        onNodeWithText("Message").performClick()
        onNodeWithText("Remove").performClick()
        onNodeWithText("Mail").performClick()
        assertEquals(listOf("message", "remove", "close", "mail $ship"), did)
    }

    @Test
    fun `someone not in the book can be added`() = card(inBook = false) {
        onNodeWithText("Add to contacts").performClick()
        assertEquals(listOf("add"), did)
    }

    @Test
    fun `our own card edits the profile instead`() = card(self = true) {
        assertTrue(!shows("Message") && !shows("Add to contacts") && !shows("Remove"))
        onNodeWithText("Edit profile").performClick()
        assertEquals(listOf("edit"), did)
    }

    @Test
    fun `a planet's @p is its name here, and a tap copies it`() = card {
        assertTrue(!shows("Show @p"), "only a comet has a word name to show instead")
        onNodeWithText(ship).performClick()
        // The sheet's own layer holds the clipboard, so the card's word for it is what is checked.
        waitUntil(timeoutMillis = 5_000) { shows("Copied") }
    }
}
