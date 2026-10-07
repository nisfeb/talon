package io.nisfeb.talon.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import io.nisfeb.talon.data.ContactEntity
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlin.test.Test
import androidx.compose.ui.test.assertCountEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A person's card: who they are, and what can be done from it for them or for us. */
@OptIn(ExperimentalTestApi::class)
class ContactProfileSheetTest {
    private val did: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    private val fetched: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    private val ship = "~mitlyn-ditrel"
    private fun card(self: Boolean = false, inBook: Boolean = true, bio: String = "bakes on weekends", trunk: Boolean = true, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        val person = ContactEntity(ship, "Mittens", bio, null, status = "at the market")
        setContent {
            CompositionLocalProvider(
                LocalClipboardManager provides object : ClipboardManager {
                    override fun getText(): AnnotatedString? = null
                    override fun setText(annotatedString: AnnotatedString) { did += "copied ${annotatedString.text}" }
                },
                io.nisfeb.talon.mail.LocalMailTo provides { to: String -> did += "mail $to" },
                LocalCallTo provides if (trunk) { to: String -> did += "call $to" } else null,
                LocalFetchProfile provides { who: String -> fetched += who },
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

    // "users need to be able to select text in the user's bio ... to copy
    // and paste". The copy itself goes to the system clipboard: the sheet
    // is its own window layer, and gives its text the platform's clipboard.
    @Test
    fun `a bio's text is selectable`() = card {
        waitForIdle() // the sheet done sliding up
        onNodeWithText("bakes on weekends").performMouseInput {
            moveTo(centerLeft); press(); moveTo(centerRight); release()
            rightClick(center)
        }
        waitForIdle()
        assertTrue(shows("Copy") && !shows("Copy link"), "a selection's menu, with no link in it")
    }

    // "right clicking on a link should have the option to copy the link".
    @Test
    fun `a link in a bio can be copied from its menu`() = card(bio = "see https://bakes.test/menu for more") {
        waitForIdle()
        // On the address itself: the line is narrower than the sheet.
        onNodeWithText("see https://bakes.test/menu for more").performMouseInput { rightClick(centerLeft + androidx.compose.ui.geometry.Offset(100f, 0f)) }
        waitForIdle()
        assertTrue(shows("Copy link"))
    }

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

    // Tlon 12.3.0 bot-liveness: the card says whether a bot is up.
    @Test
    fun `a bot's card says whether it is up`() {
        io.nisfeb.talon.urbit.BotLiveness.record(
            ship,
            kotlinx.serialization.json.buildJsonObject {
                put("bot-info", kotlinx.serialization.json.buildJsonObject { put("type", kotlinx.serialization.json.JsonPrimitive("text")); put("value", kotlinx.serialization.json.JsonPrimitive("{}")) })
                put("bot-liveness", kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("text"))
                    put("value", kotlinx.serialization.json.JsonPrimitive("""{"v":1,"state":"offline"}"""))
                })
            },
        )
        try {
            card { assertTrue(shows("Bot · Offline")) }
        } finally {
            io.nisfeb.talon.urbit.BotLiveness.record(ship, kotlinx.serialization.json.JsonObject(emptyMap()))
        }
    }

    @Test
    fun `a person's card says nothing of bots`() = card { assertTrue(!shows("Bot ·")) }

    // "if a user has trunk installed ... there should be a Call button next
    // to the message button when viewing a user's profile."
    @Test
    fun `a peer's card rings them where our ship has trunk`() = card {
        onNodeWithText("Call").performClick()
        waitForIdle()
        assertTrue("call $ship" in did, did.toString())
        assertTrue("close" in did, "the card closes as the call starts: $did")
    }

    @Test
    fun `no trunk on our ship, no Call`() = card(trunk = false) {
        onAllNodesWithText("Call").assertCountEquals(0)
        onNodeWithText("Message").assertExists()
    }

    @Test
    fun `our own card has no Call`() = card(self = true) {
        onAllNodesWithText("Call").assertCountEquals(0)
    }

    @Test
    fun `opening someone's card asks the ship for their profile`() = card {
        waitForIdle()
        assertEquals(listOf(ship), fetched)
    }

    @Test
    fun `our own card asks for nothing`() = card(self = true) {
        waitForIdle()
        assertTrue(fetched.isEmpty(), fetched.toString())
    }
}
