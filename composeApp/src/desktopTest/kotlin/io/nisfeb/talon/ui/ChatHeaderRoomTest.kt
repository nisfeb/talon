package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.nisfeb.talon.call.CallController
import io.nisfeb.talon.call.CallEngineProvider
import io.nisfeb.talon.call.PartyLine
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.ChannelGroupEntity
import io.nisfeb.talon.data.FollowedThreadEntity
import io.nisfeb.talon.data.GroupEntity
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.ThreadUnreadEntity
import io.nisfeb.talon.ui.screens.DmChatScreen
import io.nisfeb.talon.ui.screens.GroupInfoPane
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.FakeAiSettings
import io.nisfeb.talon.urbit.FakeShip
import io.nisfeb.talon.urbit.TlonChatRepo
import io.nisfeb.talon.urbit.UrbitSession
import io.nisfeb.talon.util.createAppHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A phone's chat header had no room left for the chat's name. On a
 * compact screen a channel's party line and followed threads move into
 * its Info, and the topics button is gone; a wide window's header keeps
 * them, and a DM, with no Info, keeps its threads.
 */
@OptIn(ExperimentalTestApi::class)
class ChatHeaderRoomTest {
    private val nest = "chat/~bus/general"
    private val flag = "~bus/crew"
    private val opened = CopyOnWriteArrayList<String>()

    private fun post(id: String, text: String, sent: Long, parent: String? = null, whom: String = nest) =
        MessageEntity(whom, id, if (parent == null) "~bus" else "~nec", sent, """[{"inline":["$text"]}]""", "/chat", parentId = parent)

    /** A followed thread with a new reply, in [whom]. */
    private fun followed(whom: String): suspend AppDatabase.() -> Unit = {
        groups().upsertGroups(listOf(GroupEntity(flag, "The Crew", null)))
        groups().upsertChannelGroups(listOf(ChannelGroupEntity(nest, flag)))
        messages().upsert(post("170141184506", "lunch plans", 1_000, whom = whom))
        messages().upsert(post("170141184507", "count me in", 2_000, parent = "170141184506", whom = whom))
        threadUnreads().upsert(ThreadUnreadEntity(whom, "170141184506", 1, 1, 2_000))
        followedThreads().upsert(FollowedThreadEntity(whom, "170141184506", follow = true, sent = true, atMs = 1))
    }

    private fun harness(
        seed: suspend AppDatabase.() -> Unit,
        setup: FakeShip.() -> Unit = {},
        content: @androidx.compose.runtime.Composable (AppDatabase, TlonChatRepo, FakeShip) -> Unit,
        block: ComposeUiTest.(FakeShip) -> Unit,
    ) {
        val tmp = createTempDirectory(prefix = "talon-header-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val ship = FakeShip("~zod").apply(setup)
        val repo = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod") }
        val stream = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ship.channel.events().launchIn(stream)
        runBlocking { db.seed() }
        try {
            runComposeUiTest {
                setContent { TalonTheme(darkTheme = false) { content(db, repo, ship) } }
                waitForIdle()
                block(ship)
            }
        } finally {
            stream.cancel()
            runBlocking { repo.stopAndJoinForTest() }
            db.close()
            tmp.deleteRecursively()
        }
    }

    private fun chat(whom: String = nest, compact: Boolean, block: ComposeUiTest.() -> Unit) = harness(followed(whom), content = { db, repo, _ ->
        DmChatScreen(
            db = db, repo = repo, drafts = InMemoryDraftStore(), http = createAppHttpClient(),
            aiSettings = FakeAiSettings(), uiSettings = InMemoryUiSettings(),
            ourPatp = "~zod", whom = whom,
            onBack = {}, onOpenThread = {}, onOpenConversation = {},
            onOpenImage = {}, onOpenSelfProfile = {},
            onOpenGroupInfo = {}, onPartyLine = {},
            compact = compact,
        )
    }) { block() }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun ComposeUiTest.button(label: String) = onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty()
    private fun ComposeUiTest.until(what: String, check: () -> Boolean) =
        runCatching { waitUntil(timeoutMillis = 5_000) { check() } }.getOrElse { error("never saw: $what") }

    @Test
    fun `on a phone a channel's header is its name and Info`() = chat(compact = true) {
        until("the chat") { shows("lunch plans") && button("Info") }
        assertTrue(!button("Threads you follow") && !button("Party line"), "they are in Info now")
        assertTrue(!button("Topics in this chat"), "the topics button is gone")
        assertTrue(shows("A thread you follow has new replies"), "the chip over the chat still says it")
    }

    @Test
    fun `beside a wide window's chat the header keeps them`() = chat(compact = false) {
        until("the chat") { shows("lunch plans") && button("Info") }
        assertTrue(button("Threads you follow") && button("Party line"))
        assertTrue(!button("Topics in this chat"))
    }

    @Test
    fun `a DM has no Info, so its threads stay in the header`() = chat(whom = "~nec", compact = true) {
        until("the chat") { shows("lunch plans") }
        assertTrue(button("Threads you follow") && !button("Info"))
    }

    // ─── Info ───────────────────────────────────────────────────

    private fun info(handed: Boolean, block: ComposeUiTest.(FakeShip) -> Unit) {
        var calls: CallController? = null
        harness(
            followed(nest),
            setup = {
                scries["trunk/version"] = """{"wire":9}"""
                scries["trunk/policy"] = "{}"
                scries["trunk/rooms"] = "[]"
                // ~bus hosts the group, and has told us of its line.
                scries["trunk/lines"] = """[{"host":"~bus","name":"crew","title":"The Crew","listen":false,"sfu-base":""}]"""
            },
            content = { db, repo, ship ->
                val c = androidx.compose.runtime.remember {
                    CallController(UrbitSession(ship.http, ship.session).apply { tryRestore("~zod") }, CallEngineProvider { error("no media") })
                        .apply { start() }.also { calls = it }
                }
                val line = androidx.compose.runtime.remember { PartyLine(HttpClient(MockEngine { error("unused") }), links = { _, _ -> error("no media") }) }
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                GroupInfoPane(
                    db = db, repo = repo, whom = nest,
                    onOpenCategory = {}, onOpenMembers = {},
                    party = if (handed) rememberPartyLineHere(nest, c, line, db, repo, "~zod", { "General" }, scope) else null,
                    onOpenThread = if (handed) { parent, reply -> opened += "$parent $reply" } else null,
                )
            },
        ) { ship ->
            try { block(ship) } finally { calls?.stop() }
        }
    }

    @Test
    fun `Info has the party line, and joining from it asks the group's host`() = info(handed = true) { ship ->
        until("the line's row") { shows("Party line") }
        fun joins() = ship.pokesTo("trunk").filter { p -> p.json.toString().let { "\"join-room\"" in it && "~bus" in it && "crew" in it } }
        Thread.sleep(500)
        assertTrue(joins().isEmpty(), "nothing asked before the tap")
        onNodeWithText("Party line").performClick()
        until("the join") { joins().isNotEmpty() }
        assertEquals(1, joins().size)
    }

    @Test
    fun `Info lists the threads followed here and opens one at its newest reply`() = info(handed = true) {
        until("the threads row") { shows("Threads you follow (1)") }
        assertTrue(shows("1 with new replies"))
        onNodeWithText("Threads you follow (1)").performClick()
        until("the sheet") { shows("Threads you follow here") }
        onAllNodesWithText("lunch plans", substring = true).let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        until("the thread") { opened.isNotEmpty() }
        assertEquals(listOf("170141184506 170141184507"), opened.toList())
    }

    @Test
    fun `beside a wide window's chat Info leaves them to the header`() = info(handed = false) {
        until("the pane") { shows("This channel") }
        Thread.sleep(500)
        waitForIdle()
        assertTrue(!shows("Party line") && !shows("Threads you follow"))
    }
}
