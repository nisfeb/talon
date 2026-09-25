package io.nisfeb.talon.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageMediaEntity
import io.nisfeb.talon.ui.screens.MediaListPane
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.FakeAiSettings
import io.nisfeb.talon.urbit.FakeShip
import io.nisfeb.talon.urbit.MediaCategory
import io.nisfeb.talon.urbit.SettingsSyncImpl
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a chat has shared, by kind: pictures open in the viewer, the rest open out, and each can be bookmarked. */
@OptIn(ExperimentalTestApi::class)
class MediaListPaneTest {
    private val whom = "chat/~bus/general"
    private val did: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    private fun pane(category: MediaCategory, block: ComposeUiTest.(AppDatabase) -> Unit) {
        val tmp = createTempDirectory(prefix = "talon-media-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val ship = FakeShip("~zod")
        val sync = SettingsSyncImpl(db = db, aiSettings = FakeAiSettings()).apply { attach(ship.channel) }
        val repo = TlonChatRepo(db, settingsSync = sync).apply { attachForTest(ship.channel, "~zod") }
        runBlocking {
            db.messageMedia().insertAll(listOf(
                MessageMediaEntity(whom, "1", "https://x.test/a.png", "Photo", "first picture", 10, "~bus"),
                MessageMediaEntity(whom, "2", "https://x.test/b.png", "Photo", "second picture", 20, "~bus"),
                MessageMediaEntity(whom, "3", "https://news.example/story", "Link", "A story", 30, "~nec"),
                MessageMediaEntity(whom, "4", "https://x.test/plan.pdf", "File", "plan.pdf", 40, "~nec"),
            ))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ship.channel.events().launchIn(scope)
        try {
            runComposeUiTest {
                setContent {
                    CompositionLocalProvider(LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) { did += "open $uri" } }) {
                        TalonTheme(darkTheme = false) {
                            MediaListPane(
                                db = db, repo = repo, http = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }),
                                whom = whom, category = category,
                                onOpenImageList = { urls, i -> did += "viewer ${urls.size} at ${urls[i]}" },
                            )
                        }
                    }
                }
                block(db)
            }
        } finally {
            scope.cancel()
            db.close()
            tmp.deleteRecursively()
        }
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `pictures open in the viewer, at the one tapped`() = pane(MediaCategory.Photo) { _ ->
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithContentDescription("first picture").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithContentDescription("first picture")[0].performClick()
        assertEquals(listOf("viewer 2 at https://x.test/a.png"), did)
    }

    @Test
    fun `links and files open out`() {
        pane(MediaCategory.Link) { _ ->
            waitUntil(timeoutMillis = 5_000) { shows("A story") }
            onNodeWithText("A story").performClick()
        }
        pane(MediaCategory.File) { _ ->
            waitUntil(timeoutMillis = 5_000) { shows("plan.pdf") }
            onNodeWithText("plan.pdf").performClick()
        }
        assertEquals(listOf("open https://news.example/story", "open https://x.test/plan.pdf"), did)
    }

    @Test
    fun `a long press bookmarks the message it came in, and then offers to remove it`() = pane(MediaCategory.Link) { db ->
        waitUntil(timeoutMillis = 5_000) { shows("A story") }
        onNodeWithText("A story").performTouchInput { longClick() }
        onNodeWithText("Bookmark").performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { db.bookmarks().all() }.any { it.whom == whom && it.postId == "3" } }
        onNodeWithText("A story").performTouchInput { longClick() }
        waitUntil(timeoutMillis = 5_000) { shows("Remove bookmark") }
    }

    @Test
    fun `nothing shared says so`() = pane(MediaCategory.Video) { _ ->
        waitUntil(timeoutMillis = 5_000) { shows("Nothing yet") }
    }
}
