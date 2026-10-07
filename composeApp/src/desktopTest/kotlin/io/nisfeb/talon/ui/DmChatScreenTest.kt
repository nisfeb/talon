package io.nisfeb.talon.ui

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
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.performClick
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
import io.nisfeb.talon.util.createAppHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The conversation screen end to end: what it shows from the database,
 * and what typing and sending does, down to the poke a [FakeShip]
 * receives. The repo and the rows underneath have their own tests;
 * these are the wiring between them and the screen.
 */
@OptIn(ExperimentalTestApi::class)
class DmChatScreenTest {
    private val threads = mutableListOf<String>()

    private fun chat(
        seed: suspend AppDatabase.() -> Unit = {},
        whom: String = "~bus",
        prepare: FakeShip.() -> Unit = {},
        jumpTo: String? = null,
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
        try {
            runComposeUiTest {
                setContent {
                    TalonTheme(darkTheme = false) {
                        DmChatScreen(
                            db = db, repo = repo, drafts = InMemoryDraftStore(), http = createAppHttpClient(),
                            aiSettings = FakeAiSettings(), uiSettings = InMemoryUiSettings(),
                            ourPatp = "~zod", whom = whom,
                            onBack = {}, onOpenThread = { threads += it }, onOpenConversation = {},
                            onOpenImage = {}, onOpenSelfProfile = {},
                            initialScrollMessageId = jumpTo,
                        )
                    }
                }
                waitForIdle()
                block(ship, db)
            }
        } finally {
            stream.cancel()
            runBlocking { repo.stopAndJoinForTest() }
            db.close()
            tmp.deleteRecursively()
        }
    }

    private fun msg(id: String, author: String, text: String, sent: Long, deleted: Boolean = false, parent: String? = null) =
        MessageEntity("~bus", id, author, sent, """[{"inline":["$text"]}]""", "/chat", isDeleted = deleted, parentId = parent)

    private fun ComposeUiTest.send(text: String) {
        onNode(hasSetTextAction()).performTextInput(text)
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
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
        onNodeWithText("Show more").performClick()
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
        // takes a right-click): the Message actions button beside them. In
        // a cold JVM the list is still settling and the first click can be
        // lost, as a person's would be; they click again, and so does this.
        val opened = { onAllNodesWithText("Copy text").fetchSemanticsNodes().isNotEmpty() }
        for (attempt in 1..3) {
            // A message's button sits at the top of its block, so it is the
            // lowest one at or above the words: the nearest could be the next
            // message's. Measured once the list is still, not mid-scroll.
            waitForIdle()
            val y = onNodeWithText(text).fetchSemanticsNode().boundsInRoot.center.y
            val buttons = onAllNodesWithContentDescription("Message actions")
            // Under load the list can still be laying out; no button yet is
            // another try, not a failure.
            val nearest = buttons.fetchSemanticsNodes().indices.filter { buttons[it].fetchSemanticsNode().boundsInRoot.top <= y }
                .maxByOrNull { buttons[it].fetchSemanticsNode().boundsInRoot.top } ?: continue
            buttons[nearest].performClick()
            if (runCatching { waitUntil(timeoutMillis = 1_500) { opened() } }.isSuccess) return
        }
        waitUntil(timeoutMillis = 1_000) { opened() }
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
        println("TRACE shown: " + (1..40).filter { onAllNodesWithText("post $it", substring = false).fetchSemanticsNodes().isNotEmpty() })
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("New").fetchSemanticsNodes().isNotEmpty() }
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
        assertTrue(ship.scried.none { "/older/" in it && "/30/" in it }, "posts kept here were asked of the ship: ${ship.scried.filter { "/older/" in it }}")
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("post 000").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `a jump to a post older than the window lands on it`() = chat(seed = long(260), jumpTo = "~bus/1701411845005") { _, _ ->
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("post 005").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("post 005").assertIsDisplayed()
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
}

