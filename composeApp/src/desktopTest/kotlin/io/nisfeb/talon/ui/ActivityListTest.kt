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
 * The activity feed: each kind of event says who did what and opens
 * where it happened, a mention at the chat, a reaction at its message,
 * a reaction to a reply in its thread; empty tabs say so; a feed the
 * ship does not answer for is an error to retry, never an empty feed.
 */
@OptIn(ExperimentalTestApi::class)
class ActivityListTest {
    private val went = CopyOnWriteArrayList<String>()
    private lateinit var repo: TlonChatRepo

    private val feed = """{"all":[{"source-key":"ship/~bus","events":[
        {"time":"300","event":{"dm-post-mention":{"mention-author":"~bus","content":[{"inline":["hey ~zod"]}]}}},
        {"time":"200","event":{"dm-react":{"key":{"id":"~nec/1.000"},"parent":null,"author":"~nec","react":"🎉"}}},
        {"time":"100","event":{"dm-react":{"key":{"id":"~dev/2.000"},"parent":{"id":"~dev/1.000"},"author":"~dev","react":"👍"}}}
    ]}]}"""

    private fun activity(prepare: FakeShip.() -> Unit = { scries["activity/v6/feed/init/30"] = feed }, block: ComposeUiTest.(FakeShip) -> Unit) {
        val tmp = createTempDirectory(prefix = "talon-activity-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
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
    fun `an empty tab says what would be there`() = activity {
        waitUntil(timeoutMillis = 5_000) { shows("~bus · Mentioned you") }
        onNodeWithText("Replies").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("No replies to your posts yet.") }
        onNodeWithText("Mentions").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Nobody has mentioned you yet.") }
    }

    @Test
    fun `a feed the ship does not answer for says so, and Retry asks again`() = activity(prepare = {}) { ship ->
        waitUntil(timeoutMillis = 5_000) { shows("Couldn't load activity.") }
        assertTrue(!shows("No activity yet"), "no answer is not an empty feed")
        val asked = ship.scried.count { it.startsWith("activity/") }
        ship.scries["activity/v6/feed/init/30"] = feed
        onNodeWithText("Retry").performClick()
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
