package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.NoticeEntity
import io.nisfeb.talon.ui.screens.ActivityList
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.FakeShip
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The holistic Activity feed: chat events (the ship's %activity, folded into
 * one Chat chip) and non-chat notices (an Assistant/Account chip per live
 * domain) in one timeline. Each row opens where it happened; a notice opens
 * its feature and marks itself read; a feed the ship does not answer for is an
 * error to retry, never an empty feed.
 */
@OptIn(ExperimentalTestApi::class)
class ActivityListTest {
    private val went = CopyOnWriteArrayList<String>()
    private lateinit var repo: TlonChatRepo
    private lateinit var db: AppDatabase

    private val feed = """{"all":[{"source-key":"ship/~bus","events":[
        {"time":"300","event":{"dm-post-mention":{"mention-author":"~bus","content":[{"inline":["hey ~zod"]}]}}},
        {"time":"200","event":{"dm-react":{"key":{"id":"~nec/1.000"},"parent":null,"author":"~nec","react":"🎉"}}},
        {"time":"100","event":{"dm-react":{"key":{"id":"~dev/2.000"},"parent":{"id":"~dev/1.000"},"author":"~dev","react":"👍"}}}
    ]}]}"""

    private fun activity(
        prepare: FakeShip.() -> Unit = { scries["activity/v6/feed/init/30"] = feed },
        block: ComposeUiTest.(FakeShip) -> Unit,
    ) {
        val tmp = createTempDirectory(prefix = "talon-activity-").toFile()
        db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val ship = FakeShip("~zod").apply(prepare)
        repo = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod") }
        try {
            runComposeUiTest {
                setContent {
                    TalonTheme(darkTheme = false) {
                        ActivityList(
                            db = db, repo = repo,
                            onOpenConversation = { went += "chat $it" },
                            onOpenReply = { w, p, r -> went += "reply $w $p $r" },
                            onOpenPost = { w, p -> went += "post $w $p" },
                            onOpenNotice = { went += "notice $it" },
                        )
                    }
                }
                block(ship)
            }
        } finally {
            runBlocking { repo.stopAndJoinForTest() }
            db.close()
            tmp.deleteRecursively()
        }
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun ComposeUiTest.tapRowOf(who: String) = onAllNodesWithText("$who ·", substring = true)[0].performClick()
    private fun ComposeUiTest.tap(text: String) = onAllNodesWithText(text, substring = true)[0].performClick()

    @Test
    fun `each event opens where it happened`() = activity {
        waitUntil(timeoutMillis = 5_000) { shows("~bus · Mentioned you") }
        assertTrue(shows("hey ~zod"), "what was said, under who said it")
        tapRowOf("~bus")
        tapRowOf("~nec")
        tapRowOf("~dev")
        assertEquals(listOf("chat ~bus", "post ~bus ~nec/1000", "reply ~bus ~dev/1000 ~dev/2000"), went.toList())
    }

    @Test
    fun `chat activity folds into one Chat chip, no mentions or replies tabs`() = activity {
        waitUntil(timeoutMillis = 5_000) { shows("~bus · Mentioned you") }
        assertTrue(onAllNodesWithText("Replies").fetchSemanticsNodes().isEmpty(), "no Replies tab")
        assertTrue(onAllNodesWithText("Mentions").fetchSemanticsNodes().isEmpty(), "no Mentions tab")
        onNodeWithText("Chat").performClick()
        assertTrue(shows("~bus · Mentioned you"), "the Chat chip still shows the chat activity")
    }

    @Test
    fun `a notice shows under its domain chip and in All, opens and marks read`() = activity {
        waitUntil(timeoutMillis = 5_000) { shows("~bus · Mentioned you") }
        runBlocking {
            db.notices().put(NoticeEntity("loop:1", "assistant", "loop-result", "Loop: digest", "3 new things", 500, false, "loop:1"))
        }
        // In the merged All timeline, and it lights up an Assistant chip.
        waitUntil(timeoutMillis = 5_000) { shows("Loop: digest") }
        waitUntil(timeoutMillis = 5_000) { shows("Assistant · 1") }
        // The Assistant chip filters to just the notice, not the chat events.
        tap("Assistant · 1")
        waitUntil(timeoutMillis = 5_000) { shows("Loop: digest") && !shows("~bus · Mentioned you") }
        // Tapping the notice deep-links by its target and marks it read, so
        // the chip's unread count clears.
        onNodeWithText("Loop: digest").performClick()
        waitUntil(timeoutMillis = 5_000) { went.contains("notice loop:1") }
        waitUntil(timeoutMillis = 5_000) { !shows("Assistant · 1") && shows("Assistant") }
    }

    @Test
    fun `a feed the ship does not answer for says so, and Retry asks again`() = activity(prepare = {}) { ship ->
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Couldn't load activity", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(!shows("No activity yet"), "no answer is not an empty feed")
        val asked = ship.scried.count { it.startsWith("activity/") }
        ship.scries["activity/v6/feed/init/30"] = feed
        onNodeWithText("Try again").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("~bus · Mentioned you") }
        assertTrue(ship.scried.count { it.startsWith("activity/") } > asked)
    }

    @Test
    fun `a refresh with no answer keeps what was showing`() = activity { ship ->
        waitUntil(timeoutMillis = 5_000) { shows("~bus · Mentioned you") }
        ship.scries.remove("activity/v6/feed/init/30")
        kotlinx.coroutines.runBlocking { runCatching { repo.fetchActivityFeed() } }
        waitForIdle()
        assertTrue(shows("~bus · Mentioned you"), "the feed was not wiped by a failed refresh")
    }
}
