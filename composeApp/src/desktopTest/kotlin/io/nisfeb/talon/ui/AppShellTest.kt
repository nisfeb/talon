package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.compose.App
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.update.NoopUpdateInstallerHook
import io.nisfeb.talon.update.StaticUpdateRuntime
import io.nisfeb.talon.update.UpdateState
import io.nisfeb.talon.urbit.FakeAiSettings
import io.nisfeb.talon.urbit.FakeShip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The whole desktop app against a [FakeShip]: getting into a section and,
 * above all, out of it again. Sections shipped stuck more than once
 * (CLAUDE.md, rule 7).
 */
@OptIn(ExperimentalTestApi::class)
class AppShellTest {

    private fun app(seed: suspend AppDatabase.() -> Unit = {}, block: ComposeUiTest.(FakeShip) -> Unit) {
        val tmp = createTempDirectory(prefix = "talon-app-").toFile()
        val ship = FakeShip("~zod")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            runComposeUiTest {
                setContent {
                    Box(Modifier.size(width = 1200.dp, height = 800.dp)) {
                        App(
                            http = ship.http,
                            sessionStore = ship.session,
                            aiSettings = FakeAiSettings(),
                            createDb = { key ->
                                Room.databaseBuilder<AppDatabase>(File(tmp, "$key.db").absolutePath)
                                    .setDriver(BundledSQLiteDriver())
                                    .fallbackToDestructiveMigration(dropAllTables = true)
                                    .build().also { runBlocking { it.seed() } }
                            },
                            drafts = InMemoryDraftStore(),
                            updateState = UpdateState(scope, StaticUpdateRuntime(), NoopUpdateInstallerHook()),
                        )
                    }
                }
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithContentDescription("New message").fetchSemanticsNodes().isNotEmpty()
                }
                block(ship)
            }
        } finally {
            scope.cancel()
            // The app closes its databases itself, two seconds after it
            // goes, so work still in flight can finish. Closing them here
            // too pulled SQLite out from under a running query.
            tmp.deleteRecursively()
        }
    }

    private fun ComposeUiTest.present(description: String) =
        onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.showing(text: String) =
        onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.home() = waitUntil(timeoutMillis = 5_000) { present("New message") && present("Chats") }

    @Test
    fun `every full-screen section opens from the rail and its Back returns to the list`() = app {
        for ((item, marker) in listOf(
            "My profile" to "Edit profile",
            "Watchwords" to "Add a watchword",
            "Administration" to "Administration",
            "Invites" to "No pending invites",
            "Settings" to "Appearance",
        )) {
            onNodeWithContentDescription(item).performClick()
            waitUntil(timeoutMillis = 5_000) { showing(marker) }
            assertTrue(!present("Chats"), "$item takes the whole window")
            onNodeWithContentDescription("Back").performClick()
            home()
        }
    }

    @Test
    fun `the rail's tabs switch the list pane and Chats brings the list back`() = app {
        onNodeWithContentDescription("Bookmarks").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("No bookmarks yet") }
        assertTrue(!present("New message"))
        onNodeWithContentDescription("Activity").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("No activity yet") }
        assertTrue(!showing("No bookmarks yet"), "one tab at a time")
        onNodeWithContentDescription("Chats").performClick()
        home()
    }

    @Test
    fun `Escape in a chat's composer closes the chat`() = app(seed = {
        messages().upsert(MessageEntity("~bus", "~bus/170141184506", "~bus", 1_000, """[{"inline":["hello there"]}]""", "/chat"))
    }) {
        onNodeWithText("DMs").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("hello there") }
        onNodeWithText("~bus").performClick()
        waitUntil(timeoutMillis = 5_000) { !showing("Select a chat to begin") }
        onNode(hasSetTextAction()).performClick() // typing in the chat
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Escape) }
        waitUntil(timeoutMillis = 5_000) { showing("Select a chat to begin") }
    }

    @Test
    fun `closing a section by mouse leaves the keyboard working, and a field keeps its focus`() = app(seed = {
        messages().upsert(MessageEntity("~bus", "~bus/170141184506", "~bus", 1_000, """[{"inline":["hello there"]}]""", "/chat"))
    }) {
        onNodeWithContentDescription("My profile").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("Edit profile") }
        onNodeWithText("Edit profile").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(hasSetTextAction())[0].performClick() // typing in the section
        onNodeWithContentDescription("Back").performClick() // closed by mouse: what had focus is gone
        home()
        onAllNodes(isRoot()).onFirst().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Comma) } }
        waitUntil(timeoutMillis = 5_000) { showing("Appearance") }
        onNodeWithContentDescription("Back").performClick()
        home()
        // Taking focus back is only for when nothing has it: a field
        // clicked into keeps it.
        onNodeWithText("DMs").performClick()
        waitUntil(timeoutMillis = 5_000) { showing("hello there") }
        onNodeWithText("~bus").performClick()
        waitUntil(timeoutMillis = 5_000) { !showing("Select a chat to begin") }
        onNode(hasSetTextAction()).performClick()
        Thread.sleep(300)
        onNode(hasSetTextAction()).assertIsFocused()
    }
}
