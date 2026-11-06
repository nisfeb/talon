package io.nisfeb.talon.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.onFirst
import io.nisfeb.talon.data.ChannelGroupEntity
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.ReactionEntity
import io.nisfeb.talon.ui.screens.DmChatScreen
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.FakeAiSettings
import io.nisfeb.talon.urbit.FakeShip
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import androidx.compose.ui.graphics.compositeOver
import io.ktor.client.engine.mock.respond
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * The conversation screen end to end: what it shows from the database,
 * and what typing and sending does, down to the poke a [FakeShip]
 * receives. The repo and the rows underneath have their own tests;
 * these are the wiring between them and the screen.
 */
@OptIn(ExperimentalTestApi::class)
class DmChatScreenTest {
    private val threads = mutableListOf<String>()

    /** What a test changes under the screen: the chat it shows, and whether it is there at all. */
    private class Host(whom: String) {
        var whom by androidx.compose.runtime.mutableStateOf(whom)
        var shown by androidx.compose.runtime.mutableStateOf(true)
    }

    private fun chat(
        seed: suspend AppDatabase.() -> Unit = {},
        whom: String = "~bus",
        prepare: FakeShip.() -> Unit = {},
        jumpTo: String? = null,
        host: Host = Host(whom),
        drafts: DraftStore = InMemoryDraftStore(),
        ai: io.nisfeb.talon.ai.AiSettingsRepository = FakeAiSettings(),
        ui: io.nisfeb.talon.ui.UiSettings = InMemoryUiSettings(),
        /** Ships put in the owner's contact book before the chat opens. */
        book: List<String> = emptyList(),
        /** Bubble colours as a theme would set them. */
        bubbleColors: BubbleColors? = null,
        /** Handed the theme's colours, for a test that reads pixels. */
        scheme: (androidx.compose.material3.ColorScheme) -> Unit = {},
        block: ComposeUiTest.(FakeShip, AppDatabase) -> Unit,
    ) {
        val tmp = createTempDirectory(prefix = "talon-dmchat-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val ship = FakeShip("~zod").apply(prepare)
        val repo = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod") }
        // Acks reach the waiting pokes only while the stream is read.
        val stream = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ship.channel.events().launchIn(stream)
        runBlocking { db.seed() }
        runBlocking { book.forEach { repo.addContact(it) } }
        try {
            runComposeUiTest {
                setContent {
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.ui.platform.LocalClipboardManager provides object : androidx.compose.ui.platform.ClipboardManager {
                            override fun getText(): androidx.compose.ui.text.AnnotatedString? = null
                            override fun setText(annotatedString: androidx.compose.ui.text.AnnotatedString) { copied += annotatedString.text }
                        },
                    ) {
                    TalonTheme(darkTheme = false) {
                    scheme(androidx.compose.material3.MaterialTheme.colorScheme)
                    androidx.compose.runtime.CompositionLocalProvider(LocalBubbleColors provides (bubbleColors ?: LocalBubbleColors.current)) {
                        if (host.shown) DmChatScreen(
                            db = db, repo = repo, drafts = drafts, http = offline,
                            aiSettings = ai, uiSettings = ui,
                            ourPatp = "~zod", whom = host.whom,
                            onBack = {}, onOpenThread = { threads += it }, onOpenConversation = {},
                            onOpenImage = {}, onOpenSelfProfile = {},
                            initialScrollMessageId = jumpTo,
                        )
                    }
                    }
                    }
                }
                waitForIdle()
                dumpingScreenOnFailure(ship) { block(ship, db) }
            }
        } finally {
            stream.cancel()
            runBlocking { repo.stopAndJoinForTest() }
            db.close()
            tmp.deleteRecursively()
        }
    }

    /** The web, as far as these tests go: nothing there. A link's preview is not fetched from the real one. */
    private val offline = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine {
        respond("", io.ktor.http.HttpStatusCode.NotFound)
    })

    /** What the screen put on the clipboard. */
    private val copied = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun msg(id: String, author: String, text: String, sent: Long, deleted: Boolean = false, parent: String? = null) =
        MessageEntity("~bus", id, author, sent, """[{"inline":["$text"]}]""", "/chat", isDeleted = deleted, parentId = parent)

    /**
     * The control's own click. A pointer goes where the node was when it
     * was found, and a chat still settling under a loaded CI runner moved
     * it: the click landed off the button, and nothing opened (2026-10-29).
     */
    private fun androidx.compose.ui.test.SemanticsNodeInteraction.clickAction() =
        performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)

    private fun ComposeUiTest.send(text: String) {
        onNode(hasSetTextAction()).performTextInput(text)
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
    }

    private val sixty: suspend AppDatabase.() -> Unit = {
        for (n in 1..60) messages().upsert(msg("~bus/1701411845%02d".format(n), "~bus", "message $n", n * 60_000L))
    }

    // "when a new message comes in and the user is scrolled up from the
    // bottom and can't see it the app should NOT pop back to the bottom.
    // let people read at their own pace" (sneagan, 2026-10-09)
    @Test
    fun `a message arriving while the reader is scrolled up does not move them`() = chat(seed = sixty) { _, db ->
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("message 60").fetchSemanticsNodes().isNotEmpty() }
        // A few messages up: within the twelve that used to count as the bottom.
        onNode(hasScrollAction()).performScrollToIndex(6)
        waitForIdle()
        onNodeWithText("message 54").assertIsDisplayed()
        runBlocking { db.messages().upsert(msg("~bus/170141184561", "~bus", "message 61", 61 * 60_000L)) }
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        onNodeWithText("message 54").assertIsDisplayed()
        onAllNodesWithText("message 61").assertCountEquals(0)
        // The way back is the reader's to take.
        onNodeWithContentDescription("Jump to latest").performClick()
        waitForIdle()
        onNodeWithText("message 61").assertIsDisplayed()
    }

    @Test
    fun `a message arriving while the reader is at the bottom comes into view`() = chat(seed = sixty) { _, db ->
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("message 60").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("message 60").assertIsDisplayed()
        runBlocking { db.messages().upsert(msg("~bus/170141184561", "~bus", "message 61", 61 * 60_000L)) }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("message 61").fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        onNodeWithText("message 61").assertIsDisplayed()
    }

    @Test
    fun `the conversation shows its messages and not the deleted ones`() = chat(seed = {
        messages().upsert(msg("~bus/170141184506", "~bus", "hello from bus", 1_000))
        messages().upsert(msg("~bus/170141184507", "~bus", "taken back", 2_000, deleted = true))
    }) { _, _ ->
        onNodeWithText("hello from bus").assertIsDisplayed()
        onAllNodesWithText("taken back").assertCountEquals(0)
    }

    @Test
    fun `a chat the ship did not send says so, and does not call it empty`() = chat { _, _ ->
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("Messages could not be loaded").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText("No messages yet").assertCountEquals(0)
    }

    @Test
    fun `an empty chat the ship sent invites the first message`() = chat(prepare = {
        scries["chat/v4/dm/~bus/writs/newest/50/heavy"] = """{"writs":{}}"""
    }) { _, _ ->
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("No messages yet").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText("Messages could not be loaded").assertCountEquals(0)
    }

    // sneagan, 2026-11-05: "the chat view should be a setting because I
    // don't like bubble chats even though many people do". Bubbles: ours
    // on the right with no byline. Rows: every message on the left under
    // its author's byline. The choice switches an open conversation.
    @Test
    fun `bubbles put ours on the right, and rows put every message on the left under its byline`() {
        val ui = InMemoryUiSettings()
        chat(seed = { messages().upsertAll(listOf(msg("~bus/170141184501", "~bus", "from them", 1_000), msg("~zod/170141184502", "~zod", "from me", 2_000))) }, ui = ui) { _, _ ->
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("from me").fetchSemanticsNodes().isNotEmpty() }
            fun left(text: String) = onNodeWithText(text).fetchSemanticsNode().boundsInRoot.left
            fun bylined() = onAllNodesWithText("~zod", substring = true).fetchSemanticsNodes().isNotEmpty()
            assertTrue(ui.chatBubbles.value, "bubbles unless chosen otherwise")
            assertTrue(left("from me") > left("from them") + 200f, "ours sits on the right")
            assertFalse(bylined(), "and carries no byline")
            ui.setChatBubbles(false)
            waitForIdle()
            assertEquals(left("from them"), left("from me"), "rows put both on the left")
            assertTrue(bylined(), "ours under its byline")
            ui.setChatBubbles(true)
            waitForIdle()
            assertTrue(left("from me") > left("from them") + 200f, "and back")
        }
    }

    /** A picture of one colour, kept where a chosen one would be. */
    private fun picture(rgb: Int): String {
        val f = File(createTempDirectory(prefix = "talon-picture-").toFile(), "p.png")
        val img = java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply { color = java.awt.Color(rgb); fillRect(0, 0, 64, 64); dispose() }
        javax.imageio.ImageIO.write(img, "png", f)
        return f.absolutePath
    }

    private fun assertNear(expected: Color, actual: Color, what: String) {
        val off = maxOf(kotlin.math.abs(expected.red - actual.red), kotlin.math.abs(expected.green - actual.green), kotlin.math.abs(expected.blue - actual.blue))
        assertTrue(off < 3f / 255f, "$what: expected $expected, was $actual")
    }

    private val red = Color(0xFFFF0000)

    private fun ComposeUiTest.aboveEveryMessage(): Color {
        val list = onNode(hasScrollToIndexAction()).fetchSemanticsNode().boundsInRoot
        return onRoot().captureToImage().toPixelMap()[list.center.x.toInt(), (list.top + 4f).toInt()]
    }

    // sneagan, 2026-11-05: the owner's bubbles, their contacts' (a VIP
    // colour) and everyone else's, from the theme; and 2026-11-06, on a
    // picture of the owner's. Read off the drawn pixels: inside each
    // bubble's margin, and above every message, where only the picture is.
    @Test
    fun `a contact's bubble takes the contacts' colour, others theirs, ours ours, on the chosen picture`() {
        val ui = InMemoryUiSettings().apply { setChatBackgroundImage(picture(0xFF0000)) }
        val green = Color(0xFF00FF00)
        val blue = Color(0xFF0000FF)
        val yellow = Color(0xFFFFFF00)
        chat(
            whom = "0v1.club",
            ui = ui,
            book = listOf("~nec"),
            bubbleColors = BubbleColors(mine = yellow, contact = green, theirs = blue),
            seed = {
                messages().upsertAll(listOf(
                    MessageEntity("0v1.club", "~nec/170141184501", "~nec", 1_000, """[{"inline":["from a contact"]}]""", "/chat"),
                    MessageEntity("0v1.club", "~wes/170141184502", "~wes", 2_000, """[{"inline":["from a stranger"]}]""", "/chat"),
                    MessageEntity("0v1.club", "~zod/170141184503", "~zod", 3_000, """[{"inline":["from me"]}]""", "/chat"),
                ))
            },
        ) { _, _ ->
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("from me").fetchSemanticsNodes().isNotEmpty() }
            waitUntil(timeoutMillis = 5_000) { aboveEveryMessage() == red }
            val pixels = onRoot().captureToImage().toPixelMap()
            val inset = 6.dp.value * density.density
            fun fillBeside(text: String): Color {
                val b = onNodeWithText(text).fetchSemanticsNode().boundsInRoot
                return pixels[(b.left - inset).toInt(), b.center.y.toInt()]
            }
            assertEquals(green, fillBeside("from a contact"), "a contact's bubble")
            assertEquals(blue, fillBeside("from a stranger"), "anyone else's")
            assertEquals(yellow, fillBeside("from me"), "ours")
        }
    }

    // Auto's fills are see-through; on a picture they are made solid, and
    // what is written straight on the chat (the day) sits on a pill.
    @Test
    fun `on a picture the bubbles are solid and the day is on a pill`() {
        val ui = InMemoryUiSettings().apply { setChatBackgroundImage(picture(0xFF0000)) }
        var colors: androidx.compose.material3.ColorScheme? = null
        val now = System.currentTimeMillis()
        chat(
            whom = "0v1.club",
            ui = ui,
            book = listOf("~nec"),
            scheme = { colors = it },
            seed = {
                messages().upsert(MessageEntity("0v1.club", "~nec/170141184501", "~nec", now - 60_000, """[{"inline":["from a contact"]}]""", "/chat"))
            },
        ) { _, _ ->
            // The day comes once the chat knows where its history starts.
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Today").fetchSemanticsNodes().isNotEmpty() }
            waitUntil(timeoutMillis = 5_000) { aboveEveryMessage() == red }
            val c = colors!!
            val pixels = onRoot().captureToImage().toPixelMap()
            val inset = 4.dp.value * density.density
            fun beside(text: String): Color {
                val b = onNodeWithText(text).fetchSemanticsNode().boundsInRoot
                return pixels[(b.left - inset).toInt(), b.center.y.toInt()]
            }
            assertNear(c.tertiary.copy(alpha = 0.22f).compositeOver(c.surface), beside("from a contact"), "the bubble, solid on the page")
            assertNear(c.surface.copy(alpha = 0.9f).compositeOver(red), beside("Today"), "the day's pill")
        }
    }

    @Test
    fun `rows draw on the page, with no picture behind them`() {
        val ui = InMemoryUiSettings().apply { setChatBubbles(false); setChatBackgroundImage(picture(0xFF0000)) }
        chat(ui = ui, seed = { messages().upsert(msg("~bus/170141184501", "~bus", "from them", 1_000)) }) { _, _ ->
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("from them").fetchSemanticsNodes().isNotEmpty() }
            waitForIdle()
            assertTrue(aboveEveryMessage() != red, "rows ignore the picture")
        }
    }

    // sneagan, 2026-11-05: in bubbles the hover tint ran the window's
    // width; the "⋯" beside the bubble is what says which message it is.
    @Test
    fun `in bubbles, hovering a message tints nothing across the window`() = chat(
        seed = { messages().upsert(msg("~bus/170141184501", "~bus", "from them", 1_000)) },
    ) { _, _ ->
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("from them").fetchSemanticsNodes().isNotEmpty() }
        val words = onNodeWithText("from them").fetchSemanticsNode().boundsInRoot
        val root = onRoot().fetchSemanticsNode().boundsInRoot
        // Three quarters across the row: past the bubble, short of the
        // "⋯" at a row's end, where only the row's own background is.
        fun farSide() = onRoot().captureToImage().toPixelMap()[(root.right * 0.75f).toInt(), words.center.y.toInt()]
        val before = farSide()
        onNodeWithText("from them").performMouseInput { moveTo(center) }
        waitForIdle()
        assertEquals(before, farSide(), "no tint across the row")
    }

    // Rows keep theirs: the whole row is the message there.
    @Test
    fun `in rows, hovering a message still tints its row`() {
        val ui = InMemoryUiSettings().apply { setChatBubbles(false) }
        chat(seed = { messages().upsert(msg("~bus/170141184501", "~bus", "from them", 1_000)) }, ui = ui) { _, _ ->
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("from them").fetchSemanticsNodes().isNotEmpty() }
            val words = onNodeWithText("from them").fetchSemanticsNode().boundsInRoot
            val root = onRoot().fetchSemanticsNode().boundsInRoot
            fun farSide() = onRoot().captureToImage().toPixelMap()[(root.right * 0.75f).toInt(), words.center.y.toInt()]
            // Off the row first: run after other tests, the pointer can
            // start on it, and the row is tinted before it is hovered.
            onRoot().performMouseInput { moveTo(androidx.compose.ui.geometry.Offset(1f, 1f)) }
            waitForIdle()
            val before = farSide()
            onNodeWithText("from them").performMouseInput { moveTo(center) }
            waitForIdle()
            assertTrue(before != farSide(), "the row tints, so the check above can see one")
        }
    }

    // A club is a DM of three or more: who said each run is the one thing
    // its bubbles must not drop (review, 2026-11-05: isDirect is true for
    // clubs too, so the names went).
    @Test
    fun `in a club each run's bubble is named, as in a channel`() = chat(
        whom = "0v1.club",
        seed = {
            messages().upsertAll(listOf(
                MessageEntity("0v1.club", "~nec/170141184501", "~nec", 1_000, """[{"inline":["first from nec"]}]""", "/chat"),
                MessageEntity("0v1.club", "~wes/170141184502", "~wes", 2_000, """[{"inline":["then wes"]}]""", "/chat"),
            ))
        },
    ) { _, _ ->
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("then wes").fetchSemanticsNodes().isNotEmpty() }
        // Named just above its own words, so a title naming the members
        // cannot pass for it.
        fun namedAbove(name: String, words: String): Boolean {
            val top = onNodeWithText(words).fetchSemanticsNode().boundsInRoot.top
            return onAllNodesWithText(name, substring = true).fetchSemanticsNodes().any { it.boundsInRoot.bottom <= top && it.boundsInRoot.bottom > top - 60f }
        }
        assertTrue(namedAbove("~nec", "first from nec"), "nec's run is named")
        assertTrue(namedAbove("~wes", "then wes"), "and wes's")
    }

    @Test
    fun `enter sends what was typed to the ship and shows it at once`() = chat { ship, _ ->
        send("hi bus")
        waitUntil(timeoutMillis = 20_000) { ship.pokesTo("chat").isNotEmpty() }
        val poke = ship.pokesTo("chat").single()
        assertEquals("chat-dm-action-2", poke.mark)
        assertTrue("hi bus" in poke.json.toString())
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("hi bus").fetchSemanticsNodes().isNotEmpty() }
    }

    // During an app release the ship timed writes out and the phone said
    // "send failed: … The network connection was lost." in red, and the
    // message never went.
    @Test
    fun `a message sent while the connection drops is queued and said calmly, not as an error`() =
        chat(prepare = { lose = { if (it.app == "chat") kotlinx.io.IOException("The network connection was lost.") else null } }) { _, _ ->
            send("while it drops")
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("Queued · sends when your ship is back").fetchSemanticsNodes().isNotEmpty() }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("Your ship is slow. 1 queued for when it's back.").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Copy error details").assertExists()
            assertTrue(onAllNodesWithText("send failed", substring = true).fetchSemanticsNodes().isEmpty(), "not an error")
            assertTrue(onAllNodesWithText("Not sent").fetchSemanticsNodes().isEmpty())
        }

    // A comet's name typed as it reads opened no picker, where its @p
    // did: "I want autocomplete like a normal @p gets".
    @Test
    fun `the start of a comet's word name is picked like an @p, and goes as a mention`() {
        val them = io.nisfeb.talon.ui.Mnemonym.shipForNym(
            "..bespoke.unnerved.describe.convince.inhale.charade.relieve.obey.reword.dislodge.hereby.kazoo",
        )!!
        // Known as a contact, as someone you would mention is.
        chat(whom = them, seed = {
            contacts().upsertAll(listOf(io.nisfeb.talon.data.ContactEntity(them, null, null, null)))
        }) { ship, _ ->
            onNode(hasSetTextAction()).performTextInput("hi ..besp")
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("..bespoke...kazoo", substring = true).fetchSemanticsNodes().size > 1 }
            onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) } // takes the pick
            // The box shows it as it will read once sent, by its short word
            // name, not its @p, and holds the @p.
            waitUntil(timeoutMillis = 20_000) { draft().startsWith("hi ..bespoke...kazoo") }
            assertTrue(held().startsWith("hi $them"), held())
            waitForIdle()
            onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) } // sends
            waitUntil(timeoutMillis = 20_000) { ship.pokesTo("chat").isNotEmpty() }
            val sent = ship.pokesTo("chat").single().json.toString()
            assertTrue("\"ship\":\"$them\"" in sent, sent)
        }
    }

    @Test
    fun `a message the ship refuses says it failed`() = chat(prepare = { refuse = { if (it.app == "chat") "nope" else null } }) { _, _ ->
        send("will not land")
        waitUntil(timeoutMillis = 20_000) {
            onAllNodesWithContentDescription("Send failed").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("will not land").assertIsDisplayed()
    }

    // A long post took the whole screen; in the chat it folds to ten lines.
    @Test
    fun `a long post folds in the chat, and opens`() = chat(seed = {
        // As the ship has it: a story, its lines broken, not raw newlines in a JSON string.
        val story = io.nisfeb.talon.urbit.chatTextToStory((1..20).joinToString("\n") { "line $it" }).toString()
        messages().upsert(MessageEntity("~bus", "~bus/170141184506", "~bus", 1_000, story, "/chat"))
    }) { _, _ ->
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("Show more").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Show more").clickAction()
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("Show less").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `a reply count opens the thread`() = chat(seed = {
        messages().upsert(msg("~bus/170141184506", "~bus", "a question", 1_000))
        messages().upsert(msg("~zod/170141184507", "~zod", "an answer", 2_000, parent = "~bus/170141184506"))
    }) { _, _ ->
        onNodeWithText("1 reply").performClick()
        waitForIdle()
        assertEquals(listOf("~bus/170141184506"), threads)
        onAllNodesWithText("an answer").assertCountEquals(0)
    }

    @Test
    fun `a reaction shows beneath its message`() = chat(seed = {
        messages().upsert(msg("~bus/170141184506", "~bus", "react to me", 1_000))
        reactions().upsert(ReactionEntity("~bus", "~bus/170141184506", "~nec", "🔥"))
    }) { _, _ ->
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("🔥", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    // ─── a message's menu ─────────────────────────────────────────

    private fun ComposeUiTest.menuOf(text: String) {
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        // Desktop's way in (a phone taps the row; the words' own selection
        // takes a right-click): the Message actions button beside them. A
        // message's button sits at the top of its block, so it is the
        // lowest one at or above the words: the nearest could be the next
        // message's. Under load the list can still be laying out, with no
        // button yet. Its click action, not a pointer, so a list that moves
        // meanwhile can't lose it (three pointer tries still lost it once).
        val buttons = onAllNodesWithContentDescription("Message actions")
        fun nearest(): Int? {
            val y = onNodeWithText(text).fetchSemanticsNode().boundsInRoot.center.y
            return buttons.fetchSemanticsNodes().indices.filter { buttons[it].fetchSemanticsNode().boundsInRoot.top <= y }
                .maxByOrNull { buttons[it].fetchSemanticsNode().boundsInRoot.top }
        }
        waitUntil(timeoutMillis = 20_000) { nearest() != null }
        buttons[nearest()!!].clickAction()
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("Copy text").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `from a message's menu, a thread opens and a reaction goes to the ship`() = chat(seed = {
        messages().upsert(msg("~bus/170141184506", "~bus", "hello from bus", 1_000))
    }) { ship, _ ->
        menuOf("hello from bus")
        onNodeWithText("Reply in thread").performClick()
        assertEquals(listOf("~bus/170141184506"), threads)
        menuOf("hello from bus")
        onAllNodesWithText("👍")[0].performClick()
        waitUntil(timeoutMillis = 20_000) { ship.pokesTo("chat").isNotEmpty() }
        val react = ship.pokesTo("chat").single().json.toString()
        assertTrue("add-react" in react && "~bus/170.141.184.506" in react, react)
    }

    @Test
    fun `our own message is deleted after asking, and someone else's offers no delete`() = chat(seed = {
        messages().upsert(msg("~bus/170141184506", "~bus", "theirs", 1_000))
        messages().upsert(msg("~zod/170141184507", "~zod", "mine", 2_000))
    }) { ship, _ ->
        menuOf("theirs")
        assertTrue(onAllNodesWithText("Delete").fetchSemanticsNodes().isEmpty())
        onAllNodesWithText("Copy text")[0].performClick() // closes the menu
        menuOf("mine")
        onNodeWithText("Delete").performClick()
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("Delete message?").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText("Delete").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        waitUntil(timeoutMillis = 20_000) { ship.pokesTo("chat").isNotEmpty() }
        assertTrue("\"del\"" in ship.pokesTo("chat").single().json.toString())
    }

    @Test
    fun `our own channel post is edited in place from its menu`() = chat(whom = "chat/~bus/general", seed = {
        messages().upsert(MessageEntity("chat/~bus/general", "170141184507", "~zod", 2_000, """[{"inline":["first try"]}]""", "/chat"))
    }) { ship, _ ->
        menuOf("first try")
        onNodeWithText("Edit").performClick()
        waitUntil(timeoutMillis = 20_000) {
            onAllNodes(hasSetTextAction()).fetchSemanticsNodes().any { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.EditableText)?.text == "first try" }
        }
        onNode(hasSetTextAction()).performTextReplacement("second try")
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 20_000) { ship.pokesTo("channels").isNotEmpty() }
        val edit = ship.pokesTo("channels").single().json.toString()
        assertTrue("\"edit\"" in edit && "second try" in edit && "170.141.184.507" in edit, edit)
    }

    @Test
    fun `someone else's channel post can be reported, after asking`() = chat(whom = "chat/~bus/general", seed = {
        messages().upsert(MessageEntity("chat/~bus/general", "170141184506", "~bus", 1_000, """[{"inline":["spam spam"]}]""", "/chat"))
    }) { _, _ ->
        menuOf("spam spam")
        assertTrue(onAllNodesWithText("Edit").fetchSemanticsNodes().isEmpty(), "not ours to edit")
        onNodeWithText("Report").performClick()
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("Report message?").fetchSemanticsNodes().isNotEmpty() }
    }

    // ─── an emoji alone draws large ────────────────────────────────

    private fun ComposeUiTest.heightOf(text: String) = onNodeWithText(text).fetchSemanticsNode().size.height

    @Test
    fun `a message that is only emoji draws them large, and the same emoji in a sentence does not`() = chat(seed = {
        messages().upsert(msg("~bus/170141184506", "~bus", "👍", 1_000))
        messages().upsert(msg("~bus/170141184507", "~bus", "nice 👍", 2_000))
        messages().upsert(msg("~bus/170141184508", "~bus", "😂😂😂😂", 3_000))
    }) { _, _ ->
        val alone = heightOf("👍")
        val inText = heightOf("nice 👍")
        assertTrue(alone >= inText * 1.8, "alone $alone, in a sentence $inText")
        assertTrue(heightOf("😂😂😂😂") < alone, "four are text again")
    }

    // ─── a ship's tile ─────────────────────────────────────────────

    // A comet with no picture showed "RI" (its words, ..retrieves...invests)
    // in the chat and "LM" (its @p, ~larwyx-monder-...) on its profile.
    @Test
    fun `a comet's tile is its words in the chat and on its profile`() {
        val comet = "~larwyx-monder-winpel-timwyd--timben-botfun-harpub-daplyd"
        chat(seed = {
            messages().upsert(msg("$comet/170141184506", comet, "hello", 1_000))
        }) { _, _ ->
            waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("RI").fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithText("RI").onFirst().performClick()
            // The profile is open once its "Show @p" is there.
            waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Show @p").fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithText("LM").assertCountEquals(0)
            onAllNodesWithText("RI").assertCountEquals(2)
        }
    }

    // ─── the "New" divider ─────────────────────────────────────────

    private fun unreadFrom(whom: String, firstUnreadId: String): suspend AppDatabase.() -> Unit = {
        unreads().upsert(io.nisfeb.talon.data.UnreadEntity(whom, count = 8, notifyCount = 0, recencyMs = 1, firstUnreadId = firstUnreadId))
    }

    /** True when the node draws something: more than one colour in its pixels. */
    private fun ComposeUiTest.drawn(text: String): Boolean {
        val px = onNodeWithText(text).captureToImage().toPixelMap()
        val colours = HashSet<androidx.compose.ui.graphics.Color>()
        for (x in 0 until px.width step 2) for (y in 0 until px.height step 2) colours += px[x, y]
        return colours.size > 1
    }

    // Users lost the divider to a 5 s dwell and a 3 s fade before they
    // had read down to it. It stays while the conversation is open.
    @Test
    fun `the New divider stays drawn while the chat is open`() = chat(seed = {
        (1..40).forEach { i -> messages().upsert(msg("~bus/1701411845${10 + i}", "~bus", "line $i", i * 1_000L)) }
        unreadFrom("~bus", "~bus/170141184542")()
    }) { _, _ ->
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("New").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("New").assertIsDisplayed()
        mainClock.advanceTimeBy(20_000)
        waitForIdle()
        onNodeWithText("New").assertIsDisplayed()
        assertTrue(drawn("New"), "the divider is still drawn after 20 s in the chat")
    }

    // The pinned post arrives after the list has put the divider at its
    // top; the list loses that height from the top and, holding the
    // newest message at the bottom, used to push the divider up behind
    // the banner.
    @Test
    fun `the New divider sits below a pinned post that appears after the chat opened`() = chat(whom = ours, seed = {
        ourChannel(*(1..40).map { i -> post("1701411845${10 + i}", "~bus", "post $i", i * 1_000L) }.toTypedArray())()
        // More unread than fits on screen, so the divider lands at the very top.
        unreadFrom(ours, "170141184525")()
    }) { _, db ->
        mainClock.advanceTimeBy(3_000); waitForIdle()
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("New").fetchSemanticsNodes().isNotEmpty() }
        // All of it, not a sliver: a short chat put all but a pixel of
        // it past the list's top, and on CI the pixel too.
        val list = onNode(hasScrollToIndexAction()).fetchSemanticsNode().boundsInRoot
        assertTrue(onNodeWithText("New").fetchSemanticsNode().boundsInRoot.top >= list.top, "the whole divider is in the list")
        runBlocking { db.groups().setPinnedPostId(ours, "170141184511") }
        // The banner is the row holding the "Pinned" icon.
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithContentDescription("Pinned").fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        val banner = onAllNodesWithContentDescription("Pinned").fetchSemanticsNodes().minBy { it.boundsInRoot.top }
        val divider = onNodeWithText("New").fetchSemanticsNode()
        assertTrue(
            divider.boundsInRoot.top >= banner.boundsInRoot.bottom,
            "divider top ${divider.boundsInRoot.top} under banner bottom ${banner.boundsInRoot.bottom}",
        )
        onNodeWithText("New").assertIsDisplayed()
    }

    // ─── pinning, in a channel of a group we host ──────────────────

    private val ours = "chat/~zod/general"

    private fun ourChannel(vararg posts: MessageEntity): suspend AppDatabase.() -> Unit = {
        groups().upsertChannelGroups(listOf(io.nisfeb.talon.data.ChannelGroupEntity(ours, "~zod/crew")))
        posts.forEach { messages().upsert(it) }
    }

    private fun post(id: String, author: String, text: String, sent: Long) =
        MessageEntity(ours, id, author, sent, """[{"inline":["$text"]}]""", "/chat")

    @Test
    fun `in a channel too, an emoji alone draws large`() = chat(whom = ours, seed = ourChannel(
        post("170141184506", "~bus", "🎉", 1_000), post("170141184507", "~nec", "party 🎉", 2_000),
    )) { _, _ ->
        assertTrue(heightOf("🎉") >= heightOf("party 🎉") * 1.8)
    }

    @Test
    fun `a post is pinned from its menu and shown above, then unpinned`() = chat(whom = ours, seed = ourChannel(
        post("170141184506", "~bus", "meeting at noon", 1_000), post("170141184507", "~nec", "chatter", 2_000),
    )) { ship, _ ->
        menuOf("meeting at noon")
        onNodeWithText("Pin").performClick()
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithContentDescription("Pinned").fetchSemanticsNodes().isNotEmpty() }
        waitUntil(timeoutMillis = 20_000) { ship.pokesTo("channels").isNotEmpty() }
        assertTrue("170.141.184.506" in ship.pokesTo("channels").single().json.toString())
        assertTrue(onAllNodesWithText(": meeting at noon", substring = true).fetchSemanticsNodes().isNotEmpty(), "the banner quotes it")

        menuOf("chatter")
        assertTrue(onAllNodesWithText("Pin").fetchSemanticsNodes().isNotEmpty(), "another post offers Pin, not Unpin")
        onAllNodesWithText("Copy text")[0].performClick()
        menuOf("meeting at noon")
        onNodeWithText("Unpin").performClick()
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithContentDescription("Pinned").fetchSemanticsNodes().isEmpty() }
        waitUntil(timeoutMillis = 20_000) { ship.pokesTo("channels").size == 2 }
    }

    @Test
    fun `a pin the ship refuses is taken down and says so`() = chat(
        whom = ours,
        seed = ourChannel(post("170141184506", "~bus", "meeting at noon", 1_000)),
        prepare = { refuse = { if (it.app == "channels") "not allowed" else null } },
    ) { _, _ ->
        menuOf("meeting at noon")
        onNodeWithText("Pin").performClick()
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("pin failed", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodesWithContentDescription("Pinned").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `someone else's group offers no pin`() = chat(whom = "chat/~bus/general", seed = {
        messages().upsert(MessageEntity("chat/~bus/general", "170141184506", "~bus", 1_000, """[{"inline":["their post"]}]""", "/chat"))
    }) { _, _ ->
        menuOf("their post")
        assertTrue(onAllNodesWithText("Pin").fetchSemanticsNodes().isEmpty())
    }

    // ─── emoji by name ─────────────────────────────────────────────

    /** What the composer shows. A mention shows as the name it will read as once sent. */
    private fun ComposeUiTest.draft(): String =
        onNode(hasSetTextAction()).fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.EditableText)?.text.orEmpty()

    /** What the composer holds, and sends: a mention's @p. */
    private fun ComposeUiTest.held(): String =
        onNode(hasSetTextAction()).fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.InputText)?.text.orEmpty()

    @Test
    fun `a colon and a name offer emoji, Enter takes the first, and the next Enter sends`() = chat { ship, _ ->
        onNode(hasSetTextAction()).performTextInput("lunch :taco")
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText(":taco:").fetchSemanticsNodes().isNotEmpty() }
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 20_000) { draft() == "lunch 🌮 " }
        assertTrue(ship.pokesTo("chat").isEmpty(), "picking is not sending")
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 20_000) { ship.pokesTo("chat").isNotEmpty() }
        assertTrue("🌮" in ship.pokesTo("chat").single().json.toString())
    }

    @Test
    fun `arrows move through the emoji offered, and Tab takes the one highlighted`() = chat { _, _ ->
        val offered = io.nisfeb.talon.ui.EmojiCatalog.search("heart", limit = 6)
        onNode(hasSetTextAction()).performTextInput(":heart")
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText(offered[1].shortcode).fetchSemanticsNodes().isNotEmpty() }
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.Tab) }
        waitUntil(timeoutMillis = 20_000) { draft() == "${offered[1].glyph} " }
    }

    // ─── people, commands, and the last thing said ─────────────────

    @Test
    fun `an @ and a name offer people, and Enter puts them in, shown by name`() = chat(seed = {
        contacts().upsertAll(listOf(io.nisfeb.talon.data.ContactEntity("~sampel-palnet", "Sam", null, null)))
        messages().upsert(msg("~bus/170141184506", "~bus", "hello", 1_000))
    }) { ship, _ ->
        onNode(hasSetTextAction()).performTextInput("ask @Sa")
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("Sam", substring = true).fetchSemanticsNodes().isNotEmpty() }
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        // Shown as it will read once sent; held, and sent, as the @p.
        waitUntil(timeoutMillis = 20_000) { draft() == "ask Sam " }
        assertEquals("ask ~sampel-palnet ", held())
        assertTrue(ship.pokesTo("chat").isEmpty())
    }

    @Test
    fun `a slash offers commands, and Tab fills one in`() = chat { _, _ ->
        onNode(hasSetTextAction()).performTextInput("/pol")
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("poll", substring = true).fetchSemanticsNodes().isNotEmpty() }
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Tab) }
        waitUntil(timeoutMillis = 20_000) { draft() == "/poll " }
    }

    @Test
    fun `Up in an empty channel composer edits the last post you made`() = chat(whom = "chat/~bus/general", seed = {
        messages().upsert(MessageEntity("chat/~bus/general", "170141184506", "~zod", 1_000, """[{"inline":["older"]}]""", "/chat"))
        messages().upsert(MessageEntity("chat/~bus/general", "170141184507", "~zod", 2_000, """[{"inline":["newest of mine"]}]""", "/chat"))
        messages().upsert(MessageEntity("chat/~bus/general", "170141184508", "~bus", 3_000, """[{"inline":["theirs"]}]""", "/chat"))
    }) { ship, _ ->
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("theirs").fetchSemanticsNodes().isNotEmpty() }
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.DirectionUp) }
        waitUntil(timeoutMillis = 20_000) { draft() == "newest of mine" }
        onNode(hasSetTextAction()).performTextReplacement("newest, fixed")
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 20_000) { ship.pokesTo("channels").isNotEmpty() }
        val edit = ship.pokesTo("channels").single().json.toString()
        assertTrue("\"edit\"" in edit && "170.141.184.507" in edit && "newest, fixed" in edit, edit)
    }

    // ─── the window: a long chat is not read whole on every write ─────

    private fun long(n: Int): suspend AppDatabase.() -> Unit = {
        messages().upsertAll((0 until n).map { i -> msg("~bus/1701411845%03d".format(i), "~bus", "post %03d".format(i), 1_000L * (i + 1)) })
    }

    @Test
    fun `a long chat shows its newest, and scrolling back shows the rest kept here, not asked of the ship`() = chat(seed = long(260)) { ship, _ ->
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("post 259").fetchSemanticsNodes().isNotEmpty() }
        // The first window: the newest 200 and the day's divider. At its top
        // the rest kept here is taken in, and the ship is not asked.
        onNode(hasScrollAction()).performScrollToIndex(200)
        waitUntil(timeoutMillis = 20_000) { runCatching { onNode(hasScrollAction()).performScrollToIndex(260) }.isSuccess }
        // Past the oldest kept here (post 000) the ship is asked, rightly;
        // CI's scroll sometimes got there before this looked.
        val asked = ship.scried.filter { "/older/" in it && "/30/" in it }
        assertTrue(asked.all { "/1701411845000/" in it }, "posts kept here were asked of the ship: $asked")
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("post 000").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `a jump to a post older than the window lands on it`() = chat(seed = long(260), jumpTo = "~bus/1701411845005") { _, _ ->
        // Paging 260 rows in: 20 s, as the pinned-post test below waits. At
        // 10 s it timed out under the full suite's load (2026-10-09).
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("post 005").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("post 005").assertIsDisplayed()
    }

    // B6-1: a notification names its post as %activity does, dotted, and
    // in a channel with its author. Rows keep it undotted and bare. Each
    // in a chat no other test opens: the first frame's rows are kept per
    // chat for the process, and another test's broke the composition.
    @Test
    fun `a notification's dotted id lands on its post in a DM`() = chat(
        whom = "~nec",
        seed = { messages().upsertAll((0 until 260).map { i -> MessageEntity("~nec", "~nec/1701411845%03d".format(i), "~nec", 1_000L * (i + 1), """[{"inline":["post %03d"]}]""".format(i), "/chat") }) },
        jumpTo = "~nec/1.701.411.845.005",
    ) { _, _ ->
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("post 005").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("post 005").assertIsDisplayed()
        assertTrue(onAllNodesWithText("isn't loaded", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a notification's dotted id lands on its post in a channel`() = chat(
        whom = "chat/~bus/dotted",
        seed = { messages().upsertAll((0 until 260).map { i -> MessageEntity("chat/~bus/dotted", "1701411845%03d".format(i), "~bus", 1_000L * (i + 1), """[{"inline":["post %03d"]}]""".format(i), "/chat") }) },
        jumpTo = "~bus/1.701.411.845.005",
    ) { _, _ ->
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("post 005").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("post 005").assertIsDisplayed()
        assertTrue(onAllNodesWithText("isn't loaded", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a pinned post older than the window is gone to from the banner`() = chat(
        whom = "chat/~bus/general",
        seed = {
            messages().upsertAll((0 until 260).map { i ->
                MessageEntity("chat/~bus/general", "1701411845%03d".format(i), "~bus", 1_000L * (i + 1), """[{"inline":["post %03d"]}]""".format(i), "/chat")
            })
            groups().upsertChannelGroups(listOf(ChannelGroupEntity("chat/~bus/general", "~bus/garden", pinnedPostId = "1701411845005")))
        },
    ) { _, _ ->
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("post 259").fetchSemanticsNodes().isNotEmpty() }
        // The banner may show the post's words; the row itself is not drawn.
        val before = onAllNodesWithText("post 005").fetchSemanticsNodes().size
        onAllNodesWithContentDescription("Pinned").onFirst().performClick()
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("post 005").fetchSemanticsNodes().size > before }
        onAllNodesWithText("Pinned message is older than what's loaded", substring = true).assertCountEquals(0)
    }

    // A short chat keeps its top in view, so every layout change asked
    // the ship for an older page again: eight failed pages, sixty-four
    // scries, in the first tenth of a second (found chasing CI's
    // timeouts, 2026-10-07). A failure now waits before the next ask.
    @Test
    fun `a failed older page is not asked again at once`() = chat(seed = {
        messages().upsert(msg("~bus/170141184506", "~bus", "one", 1_000))
        messages().upsert(msg("~bus/170141184507", "~bus", "two", 2_000))
    }) { ship, db ->
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("two").fetchSemanticsNodes().isNotEmpty() }
        waitUntil(timeoutMillis = 20_000) { ship.scried.any { "/writs/older/" in it } }
        Thread.sleep(500)
        val first = ship.scried.count { "/writs/older/" in it }
        // New messages change the layout with the top still in view.
        listOf("three", "four", "five").forEachIndexed { i, text ->
            runBlocking { db.messages().upsert(msg("~bus/17014118450${8 + i}", "~bus", text, 3_000L + i)) }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        }
        Thread.sleep(500)
        waitForIdle()
        assertEquals(first, ship.scried.count { "/writs/older/" in it }, "not asked again on every layout change")
    }

    // "Copy link" gave a talon:// address that neither Tlon nor a chat in
    // Talon made anything of (sneagan, 2026-10-16). A channel post copies
    // Tlon's reference, which both show as a card.
    @Test
    fun `copy link on a channel post copies Tlon's reference to it`() = chat(whom = "chat/~darduc-mitfen/v3l8bgc0", seed = {
        messages().upsert(MessageEntity("chat/~darduc-mitfen/v3l8bgc0", "170141184508207337420778996591277113344", "~bus", 1_000, """[{"inline":["a post worth sharing"]}]""", "/chat"))
    }) { _, _ ->
        menuOf("a post worth sharing")
        onNodeWithText("Copy link").performClick()
        waitForIdle()
        assertEquals(listOf("/1/chan/chat/~darduc-mitfen/v3l8bgc0/msg/170.141.184.508.207.337.420.778.996.591.277.113.344"), copied.toList())
    }

    @Test
    fun `copy link in a DM stays Talon's own link, since nobody else could open the post`() = chat(seed = {
        messages().upsert(msg("~bus/170141184506", "~bus", "just between us", 1_000))
    }) { _, _ ->
        menuOf("just between us")
        onNodeWithText("Copy link").performClick()
        waitForIdle()
        assertTrue(copied.single().startsWith("talon://chat/"), copied.toString())
    }

    @Test
    fun `a pasted post reference goes to the ship as the post's cite`() = chat { ship, _ ->
        send("/1/chan/chat/~darduc-mitfen/v3l8bgc0/msg/170.141.184.508.207.337.420.778.996.591.277.113.344")
        waitUntil(timeoutMillis = 20_000) { ship.pokesTo("chat").isNotEmpty() }
        val sent = ship.pokesTo("chat").single().json.toString()
        assertTrue(
            "\"cite\":{\"chan\":{\"nest\":\"chat/~darduc-mitfen/v3l8bgc0\",\"where\":\"/msg/170.141.184.508.207.337.420.778.996.591.277.113.344\"}}" in sent,
            sent,
        )
    }

    // ─── a post the ship has not taken yet ─────────────────────────

    // Its id is only ours ("local_..."): a reaction, reply, edit, pin,
    // link, quote or follow sent with it named a post the ship never had.
    @Test
    fun `a post the ship has not taken offers only what can be done here`() = chat(whom = ours, seed = ourChannel(
        post("170141184506", "~zod", "landed earlier", 1_000),
        MessageEntity(ours, "local_170141184599", "~zod", 2_000, """[{"inline":["never landed"]}]""", "/chat", status = "failed"),
    )) { _, _ ->
        menuOf("never landed")
        for (gone in listOf("React", "Reply in thread", "Edit", "Pin", "Copy link", "Quote", "Follow thread", "Unfollow thread")) {
            assertTrue(onAllNodesWithText(gone).fetchSemanticsNodes().isEmpty(), "offered $gone")
        }
        onNodeWithText("Delete").assertExists()
        onNodeWithText("Copy text").performClick()
        // Up edits the last post the ship has, not the one it does not.
        // The box first takes the focus the menu had.
        onNode(hasSetTextAction()).performClick()
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.DirectionUp) }
        waitUntil(timeoutMillis = 20_000) { draft() == "landed earlier" }
    }

    // B6-4: a post on its way, its poke on the wire, offers no Delete:
    // the ship takes it anyway, and its echo brought it back.
    @Test
    fun `a post still sending offers no delete`() = chat(whom = ours, seed = ourChannel(
        MessageEntity(ours, "local_170141184599", "~zod", 2_000, """[{"inline":["on its way"]}]""", "/chat", status = "pending"),
    )) { _, _ ->
        menuOf("on its way")
        onNodeWithText("Copy text").assertExists()
        assertTrue(onAllNodesWithText("Delete").fetchSemanticsNodes().isEmpty(), "offered Delete")
    }

    // ─── an edit, until the ship has it ────────────────────────────

    private val general = "chat/~bus/general"

    private fun ComposeUiTest.editTo(from: String, to: String) {
        menuOf(from)
        onNodeWithText("Edit").performClick()
        waitUntil(timeoutMillis = 20_000) { draft() == from }
        onNode(hasSetTextAction()).performTextReplacement(to)
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
    }

    @Test
    fun `an edit the ship refuses keeps the edited words and says so`() = chat(
        whom = general,
        seed = { messages().upsert(MessageEntity(general, "170141184507", "~zod", 2_000, """[{"inline":["first try"]}]""", "/chat")) },
        prepare = { refuse = { if (it.app == "channels") "nope" else null } },
    ) { _, _ ->
        editTo("first try", "second try")
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("edit failed", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("second try", draft(), "the changed words are still there to save again")
        onNodeWithText("Editing message").assertExists()
    }

    @Test
    fun `an edit on its way says saving and keeps its words until the ship has it`() = chat(
        whom = general,
        seed = { messages().upsert(MessageEntity(general, "170141184507", "~zod", 2_000, """[{"inline":["first try"]}]""", "/chat")) },
        prepare = { holdPoke = 1_500 },
    ) { ship, _ ->
        editTo("first try", "second try")
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Saving…").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("second try", draft())
        // A second Enter while it is on its way does not send it twice.
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("Editing message").fetchSemanticsNodes().isEmpty() && onAllNodesWithText("Saving…").fetchSemanticsNodes().isEmpty() }
        assertEquals("", draft(), "the box is back to what it held before the edit")
        Thread.sleep(300)
        assertEquals(1, ship.pokesTo("channels").size)
    }

    // Real time: the edit is held on the ship's side while the chat goes.
    @Test
    fun `an edit saved just before leaving the chat still lands`() {
        val host = Host(general)
        chat(
            whom = general, host = host,
            seed = { messages().upsert(MessageEntity(general, "170141184507", "~zod", 2_000, """[{"inline":["first try"]}]""", "/chat")) },
            prepare = { holdPoke = 1_000 },
        ) { ship, _ ->
            editTo("first try", "second try")
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Saving…").fetchSemanticsNodes().isNotEmpty() }
            host.shown = false
            waitForIdle()
            val until = System.currentTimeMillis() + 10_000
            while (ship.pokesTo("channels").isEmpty() && System.currentTimeMillis() < until) Thread.sleep(100)
            assertTrue("second try" in ship.pokesTo("channels").single().json.toString())
        }
    }

    // ─── words as they were written ────────────────────────────────

    @Test
    fun `copy text gives a link its whole address`() = chat(seed = {
        val url = "https://example.com/articles/2026/10/a-very-long-slug"
        messages().upsert(MessageEntity("~bus", "~bus/170141184506", "~bus", 1_000, """[{"inline":["see ",{"link":{"href":"$url","content":"$url"}}]}]""", "/chat"))
    }) { _, _ ->
        menuOf("see example.com/articles…")
        onNodeWithText("Copy text").performClick()
        waitForIdle()
        assertEquals(listOf("see https://example.com/articles/2026/10/a-very-long-slug"), copied.toList())
    }

    @Test
    fun `a colon and one letter is a smiley and enter sends it`() = chat { ship, _ ->
        send("nice :D")
        waitUntil(timeoutMillis = 20_000) { ship.pokesTo("chat").isNotEmpty() }
        assertTrue("nice :D" in ship.pokesTo("chat").single().json.toString())
    }

    @Test
    fun `a restored draft takes more words after itself`() = chat(drafts = InMemoryDraftStore().apply { save("~bus", "hello") }) { _, _ ->
        waitUntil(timeoutMillis = 20_000) { draft() == "hello" }
        onNode(hasSetTextAction()).performTextInput(" there")
        waitUntil(timeoutMillis = 20_000) { draft() == "hello there" }
    }

    // A link or a notification to a post this device does not keep opened
    // the chat at its newest, with no word that the post was not there.
    @Test
    fun `a jump to a post not kept here says so and opens at the newest`() = chat(seed = {
        messages().upsert(msg("~bus/170141184506", "~bus", "hello from bus", 1_000))
    }, jumpTo = "~bus/170141184599") { _, _ ->
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("That message isn't loaded here yet", substring = true).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("hello from bus").assertIsDisplayed()
    }

    // ─── the profile sheet ─────────────────────────────────────────

    private val sam: suspend AppDatabase.() -> Unit = {
        contacts().upsertAll(listOf(io.nisfeb.talon.data.ContactEntity("~sampel-palnet", "Sam Bell", null, null)))
        messages().upsert(msg("~sampel-palnet/170141184506", "~sampel-palnet", "hi from sam", 1_000))
    }

    private fun ComposeUiTest.addSam() {
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("SB").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText("SB").onFirst().clickAction()
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("Add to contacts").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Add to contacts").performClick()
    }

    // A user report: clicking an avatar no longer showed the profile. It
    // did in the first chat; the desktop pane keeps this screen, and after
    // a switch the tap wrote the first chat's state.
    @Test
    fun `an avatar opens the profile after the pane switches chats`() {
        // msg() files every message under ~bus: open on an empty chat first.
        val host = Host("~nec")
        chat(seed = sam, host = host) { _, _ ->
            waitForIdle()
            host.whom = "~bus"
            waitForIdle()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("SB").fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithText("SB").onFirst().clickAction()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("Add to contacts").fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test
    fun `adding a contact the ship refuses says so`() = chat(seed = sam, prepare = { refuse = { if (it.app == "contacts") "nope" else null } }) { _, _ ->
        addSam()
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("add contact failed: the ship refused it").fetchSemanticsNodes().isNotEmpty() }
    }

    // Real time: the poke is held on the ship's side while the chat goes.
    @Test
    fun `a contact added just before leaving the chat is still added`() {
        val host = Host("~bus")
        chat(seed = sam, host = host, prepare = { holdPoke = 1_000 }) { ship, _ ->
            addSam()
            waitForIdle()
            host.shown = false
            waitForIdle()
            val until = System.currentTimeMillis() + 10_000
            while (ship.pokesTo("contacts").size < 2 && System.currentTimeMillis() < until) Thread.sleep(100)
            assertEquals(2, ship.pokesTo("contacts").size, "meet, then the book page")
        }
    }

    // The desktop pane keeps this screen across a chat switch, and a
    // dialog left open acted on the next chat.
    @Test
    fun `a dialog open in one chat is gone in the next`() {
        val host = Host("~bus")
        chat(seed = {
            messages().upsert(msg("~zod/170141184507", "~zod", "mine", 2_000))
        }, host = host) { _, _ ->
            menuOf("mine")
            onNodeWithText("Delete").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("Delete message?").fetchSemanticsNodes().isNotEmpty() }
            host.whom = "~nec"
            waitForIdle()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("Delete message?").fetchSemanticsNodes().isEmpty() }
        }
    }

    /**
     * A model on the owner's own Armillary that answers [answer] at
     * /v1/chat/completions, on this machine: the screen's client is its
     * own, so it is asked over a real socket. The bodies it was sent.
     */
    private fun model(answer: String, block: (base: String, bodies: List<String>) -> Unit) {
        val bodies = java.util.concurrent.CopyOnWriteArrayList<String>()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { ex ->
            bodies += ex.requestBody.readBytes().decodeToString()
            val out = answer.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        server.start()
        try { block("http://127.0.0.1:${server.address.port}/v1", bodies) } finally { server.stop(0) }
    }

    private suspend fun AppDatabase.unread25() {
        messages().upsertAll((1..25).map { msg("~bus/17000000000$it", "~bus", "line $it", 1_700_000_000_000L + it * 1_000) })
        unreads().upsert(io.nisfeb.talon.data.UnreadEntity("~bus", count = 25, notifyCount = 25, recencyMs = 1_700_000_025_000L))
    }

    private fun paidAt(base: String) = FakeAiSettings(io.nisfeb.talon.ai.paidSettings(row = io.nisfeb.talon.ai.armillaryRow(base = base)))

    // A user's catch-ups in channels came back as "null" (2026-10-31): the
    // model spent the 512 tokens thinking and sent "content": null, which
    // was read as the word.
    @Test
    fun `a catch-up the model could not finish says why, and never shows null`() = model(
        """{"choices":[{"message":{"role":"assistant","content":null},"finish_reason":"length"}]}""",
    ) { base, bodies ->
        chat(seed = { unread25() }, ai = paidAt(base)) { _, _ ->
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("Catch me up on 25 unread messages").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Catch me up on 25 unread messages").clickAction()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("Catch me up failed").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(onAllNodesWithText("the model used all its room before it answered", substring = true).fetchSemanticsNodes().isNotEmpty())
            assertTrue(onAllNodesWithText("null").fetchSemanticsNodes().isEmpty(), "not the word null")
            val sent = kotlinx.serialization.json.Json.parseToJsonElement(bodies.single()).jsonObject
            assertEquals("16384", sent["max_tokens"]?.jsonPrimitive?.content, "it asked for room to think and answer")
        }
    }

    @Test
    fun `a catch-up the model answered shows the summary`() = model(
        """{"choices":[{"message":{"role":"assistant","content":"Bus is running late."},"finish_reason":"stop"}]}""",
    ) { base, _ ->
        chat(seed = { unread25() }, ai = paidAt(base)) { _, _ ->
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("Catch me up on 25 unread messages").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Catch me up on 25 unread messages").clickAction()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("Bus is running late.").fetchSemanticsNodes().isNotEmpty() }
        }
    }

    // ─── the composer's buttons as the field grows ─────────────────

    // A user, 2026-11-02: the send button "should be bottom aligned to
    // stay put as the input field gets taller".
    @Test
    fun `send keeps its place at the field's bottom as the field grows, and sits mid-field on one line`() = chat { _, _ ->
        fun field() = onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        fun sendIcon() = onAllNodesWithContentDescription("Send")[0].fetchSemanticsNode().boundsInRoot
        onNode(hasSetTextAction()).performTextInput("one line")
        waitForIdle()
        val one = field()
        val sendOne = sendIcon()
        assertEquals(one.center.y, sendOne.center.y, 1.5f, "one line: Send in the middle, as before ($sendOne in $one)")
        onNode(hasSetTextAction()).performTextInput("\nsecond\nthird\nfourth")
        waitForIdle()
        val tall = field()
        val sendTall = sendIcon()
        assertTrue(tall.height > one.height + 20, "the field grew: $one to $tall")
        assertEquals(one.bottom - sendOne.bottom, tall.bottom - sendTall.bottom, 1.5f, "Send stayed as far above the field's bottom")
    }
}
