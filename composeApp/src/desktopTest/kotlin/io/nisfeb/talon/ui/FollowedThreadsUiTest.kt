package io.nisfeb.talon.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.ChannelGroupEntity
import io.nisfeb.talon.data.FollowedThreadEntity
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.ThreadUnreadEntity
import io.nisfeb.talon.data.threadsInOrder
import io.nisfeb.talon.ui.screens.ActivityList
import io.nisfeb.talon.ui.screens.DmChatScreen
import io.nisfeb.talon.ui.screens.ThreadList
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.FakeAiSettings
import io.nisfeb.talon.urbit.FakeShip
import io.nisfeb.talon.urbit.TlonChatRepo
import io.nisfeb.talon.util.createAppHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The way back to a thread: only threads the owner follows count, and
 * each screen that shows one says so. The chat tints only those, names
 * them in a chip over the chat and a sheet from its header, and offers
 * Follow and Unfollow on a post; Activity lists them across every chat;
 * the thread itself has the switch.
 */
@OptIn(ExperimentalTestApi::class)
class FollowedThreadsUiTest {
    private val nest = "chat/~bus/general"
    private val opened = mutableListOf<String>()
    private val newReplies = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "New replies")

    private fun post(id: String, author: String, text: String, sent: Long, parent: String? = null, whom: String = nest) =
        MessageEntity(whom, id, author, sent, """[{"inline":["$text"]}]""", "/chat", parentId = parent)

    /** Two threads under ~bus's posts: one followed, one not, each with an unread reply. */
    private val twoThreads: suspend AppDatabase.() -> Unit = {
        groups().upsertChannelGroups(listOf(ChannelGroupEntity(nest, "~bus/garden")))
        messages().upsert(post("170141184506", "~bus", "lunch plans", 1_000))
        messages().upsert(post("170141184507", "~nec", "count me in", 2_000, parent = "170141184506"))
        messages().upsert(post("170141184508", "~bus", "football chatter", 3_000))
        messages().upsert(post("170141184509", "~nec", "what a goal", 4_000, parent = "170141184508"))
        threadUnreads().upsert(ThreadUnreadEntity(nest, "170141184506", 1, 1, 2_000))
        threadUnreads().upsert(ThreadUnreadEntity(nest, "170141184508", 1, 0, 4_000))
        followedThreads().upsert(FollowedThreadEntity(nest, "170141184506", follow = true, sent = true, atMs = 1))
    }

    private fun harness(seed: suspend AppDatabase.() -> Unit, content: @androidx.compose.runtime.Composable (AppDatabase, TlonChatRepo) -> Unit, block: ComposeUiTest.(FakeShip, AppDatabase) -> Unit) {
        val tmp = createTempDirectory(prefix = "talon-threads-ui-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val ship = FakeShip("~zod")
        val repo = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod") }
        val stream = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ship.channel.events().launchIn(stream)
        runBlocking { db.seed() }
        try {
            runComposeUiTest {
                setContent { TalonTheme(darkTheme = false) { content(db, repo) } }
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

    private fun chat(seed: suspend AppDatabase.() -> Unit, block: ComposeUiTest.(FakeShip, AppDatabase) -> Unit) = harness(seed, { db, repo ->
        DmChatScreen(
            db = db, repo = repo, drafts = InMemoryDraftStore(), http = createAppHttpClient(),
            aiSettings = FakeAiSettings(), uiSettings = InMemoryUiSettings(),
            ourPatp = "~zod", whom = nest,
            onBack = {}, onOpenThread = { opened += it }, onOpenConversation = {},
            onOpenImage = {}, onOpenSelfProfile = {},
        )
    }, block)

    private fun ComposeUiTest.until(what: String, check: () -> Boolean) =
        runCatching { waitUntil(timeoutMillis = 5_000) { check() } }.getOrElse { error("never saw: $what") }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    // ─── the chat ───────────────────────────────────────────────

    @Test
    fun `only a followed thread's replies tint as new`() = chat(twoThreads) { _, _ ->
        until("both threads") { onAllNodesWithText("1 reply").fetchSemanticsNodes().size == 2 }
        assertEquals(1, onAllNodes(newReplies).fetchSemanticsNodes().size, "the followed one alone")
    }

    @Test
    fun `the chip over the chat names the followed thread with new replies, and opens it`() = chat(twoThreads) { _, _ ->
        until("the chip") { shows("A thread you follow has new replies") }
        onNodeWithText("A thread you follow has new replies").performClick()
        until("the sheet") { shows("Threads you follow here") }
        assertTrue(!shows("~bus: football chatter"), "the unfollowed one is not listed")
        // The last: the sheet's row, over the post in the chat.
        onAllNodesWithText("lunch plans", substring = true).let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        until("the thread opened") { opened == listOf("170141184506") }
    }

    @Test
    fun `the header lists the followed threads even once read`() = chat({
        twoThreads()
        threadUnreads().deleteOne(nest, "170141184506")
    }) { _, _ ->
        until("the header button") { onAllNodesWithContentDescription("Threads you follow").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(!shows("have new replies") && !shows("has new replies"), "no chip: nothing new")
        onNodeWithContentDescription("Threads you follow").performClick()
        until("the sheet") { shows("Threads you follow here") }
        assertTrue(shows("1 reply · ~nec"), "its newest reply")
    }

    @Test
    fun `a post's menu follows a thread the owner is not in, and the ship hears it`() = chat(twoThreads) { ship, db ->
        until("the post") { shows("football chatter") }
        menuOf("football chatter")
        onNodeWithText("Follow thread").performClick()
        until("the follow poke") { ship.pokesTo("activity").any { "adjust" in it.json.toString() && "170.141.184.508" in it.json.toString() } }
        until("it counts now") { onAllNodes(newReplies).fetchSemanticsNodes().size == 2 }
        menuOf("lunch plans")
        assertTrue(shows("Unfollow thread"), "a followed thread offers Unfollow")
    }

    private fun ComposeUiTest.menuOf(text: String) {
        until(text) { shows(text) }
        val open = { onAllNodesWithText("Copy text").fetchSemanticsNodes().isNotEmpty() }
        for (attempt in 1..3) {
            waitForIdle()
            val y = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().first().boundsInRoot.center.y
            val buttons = onAllNodesWithContentDescription("Message actions")
            val nearest = buttons.fetchSemanticsNodes().indices.filter { buttons[it].fetchSemanticsNode().boundsInRoot.top <= y }
                .maxByOrNull { buttons[it].fetchSemanticsNode().boundsInRoot.top } ?: continue
            buttons[nearest].performClick()
            if (runCatching { waitUntil(timeoutMillis = 1_500) { open() } }.isSuccess) return
        }
        until("the menu") { open() }
    }

    // ─── Activity ───────────────────────────────────────────────

    private val openedReplies = mutableListOf<Triple<String, String, String>>()

    private fun activity(seed: suspend AppDatabase.() -> Unit, block: ComposeUiTest.(FakeShip, AppDatabase) -> Unit) = harness(seed, { db, repo ->
        ActivityList(db = db, repo = repo, onOpenConversation = {}, onOpenReply = { w, p, r -> openedReplies += Triple(w, p, r) })
    }, block)

    @Test
    fun `Activity's Threads tab lists every followed thread, new ones first, and opens one at its newest reply`() = activity({
        twoThreads()
        messages().upsert(post("~bus/170141184510", "~bus", "a DM question", 5_000, whom = "~bus"))
        messages().upsert(post("~bus/170141184511", "~bus", "a DM answer", 6_000, parent = "~bus/170141184510", whom = "~bus"))
        threadUnreads().upsert(ThreadUnreadEntity("~bus", "~bus/170141184510", 1, 1, 6_000))
    }) { _, _ ->
        until("the tab") { shows("Threads · 2") }
        onNodeWithText("Threads · 2").performClick()
        until("the DM's thread, which counts by default") { shows("a DM question") }
        assertTrue(shows("lunch plans") && !shows("football chatter"))
        onAllNodesWithText("lunch plans", substring = true)[0].performClick()
        until("opened") { openedReplies.isNotEmpty() }
        assertEquals(Triple(nest, "170141184506", "170141184507"), openedReplies.single())
    }

    @Test
    fun `mark all read reads every followed thread with new replies`() = activity(twoThreads) { ship, db ->
        until("the tab") { shows("Threads · 1") }
        onNodeWithText("Threads · 1").performClick()
        onNodeWithText("Mark all read").performClick()
        until("the read") { ship.pokesTo("activity").any { "read" in it.json.toString() && "170.141.184.506" in it.json.toString() } }
        assertTrue(ship.pokesTo("activity").none { "170.141.184.508" in it.json.toString() }, "not the unfollowed one")
        until("the tab clears") { shows("Threads") && !shows("Threads · 1") }
    }

    // ─── the thread ─────────────────────────────────────────────

    @Test
    fun `inside a thread the switch follows and unfollows it`() = harness(twoThreads, { db, repo ->
        ThreadList(
            db = db, repo = repo, http = createAppHttpClient(), drafts = InMemoryDraftStore(),
            ourPatp = "~zod", whom = nest, parentId = "170141184508", initialScrollReplyId = null,
            onOpenConversation = {}, onOpenImage = {},
        )
    }) { ship, db ->
        until("not followed") { shows("You don't follow this thread") }
        onNodeWithText("Follow").performClick()
        until("followed") { shows("You follow this thread") }
        assertEquals(true, runBlocking { db.followedThreads().get(nest, "170141184508") }?.follow)
        onNodeWithText("Unfollow").performClick()
        until("unfollowed") { shows("You don't follow this thread") }
        until("both told") { ship.pokesTo("activity").count { "adjust" in it.json.toString() } == 2 }
    }

    // ─── the list underneath ────────────────────────────────────

    @Test
    fun `the threads list holds followed threads and a DM's with news, not unfollowed ones`() = runBlocking<Unit> {
        val tmp = createTempDirectory(prefix = "talon-threads-q-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        try {
            db.twoThreads()
            db.threadUnreads().upsert(ThreadUnreadEntity("~bus", "~bus/1", 2, 2, 9_000))
            db.threadUnreads().upsert(ThreadUnreadEntity("~nec", "~nec/2", 1, 1, 9_000))
            db.followedThreads().upsert(FollowedThreadEntity("~nec", "~nec/2", follow = false, sent = true, atMs = 1))
            val rows = threadsInOrder(db.followedThreads().streamThreads().first())
            assertEquals(listOf("~bus" to "~bus/1", nest to "170141184506"), rows.map { it.whom to it.parentPostId })
            val lunch = rows.last()
            assertEquals(listOf("~bus", "1", "~nec", "170141184507"), listOf(lunch.parentAuthor, "${lunch.replyCount}", lunch.lastReplier, lunch.lastReplyId))
        } finally {
            db.close()
            tmp.deleteRecursively()
        }
    }
}
