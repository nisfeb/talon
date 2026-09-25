package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.ui.screens.WatchwordsScreen
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.FakeAiSettings
import io.nisfeb.talon.urbit.FakeShip
import io.nisfeb.talon.urbit.SettingsSyncImpl
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The watchwords screen: a term added in the sheet finds what history
 * already holds and reaches the ship; a hit opens its message; a term
 * deleted takes its hits.
 */
@OptIn(ExperimentalTestApi::class)
class WatchwordsScreenTest {
    private val opened = mutableListOf<String>()

    private fun screen(block: ComposeUiTest.(FakeShip) -> Unit) {
        val tmp = createTempDirectory(prefix = "talon-wwui-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val ship = FakeShip("~zod")
        val syncOn = MutableStateFlow(true)
        val sync = SettingsSyncImpl(db = db, aiSettings = FakeAiSettings()).apply { attach(ship.channel) }
        val repo = TlonChatRepo(db, settingsSync = sync, watchwordsSyncEnabled = syncOn).apply { attachForTest(ship.channel, "~zod") }
        runBlocking { db.messages().upsert(MessageEntity("~bus", "~bus/1", "~bus", 5, """[{"inline":["lunch on mars"]}]""", "/chat")) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ship.channel.events().launchIn(scope)
        try {
            runComposeUiTest {
                setContent {
                    TalonTheme(darkTheme = false) {
                        WatchwordsScreen(
                            db = db, watchwords = repo.watchwords,
                            watchwordsSyncEnabled = syncOn, onSetWatchwordsSyncEnabled = { syncOn.value = it },
                            onBack = {}, onOpenConversation = { whom, id -> opened += "$whom $id" },
                        )
                    }
                }
                block(ship)
            }
        } finally {
            scope.cancel()
            db.close()
            tmp.deleteRecursively()
        }
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a term added in the sheet finds history, syncs, and its hit opens the message`() = screen { ship ->
        onNodeWithText("Add a watchword").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Add a watchword…") }
        onNode(hasSetTextAction()).performTextInput("mars")
        onNodeWithText("Add").performClick()
        waitUntil(timeoutMillis = 5_000) { ship.pokesTo("settings").any { "mars" in it.json.toString() } }
        // The hit is on the screen behind the sheet: a first tap there
        // lands on the scrim and closes the sheet, the second opens it.
        waitUntil(timeoutMillis = 5_000) { shows("lunch on mars") }
        onAllNodesWithText("lunch on mars", substring = true)[0].performClick()
        waitUntil(timeoutMillis = 5_000) { !shows("Add a watchword…") }
        onAllNodesWithText("lunch on mars", substring = true)[0].performClick()
        assertEquals(listOf("~bus ~bus/1"), opened)
    }

    @Test
    fun `deleting a term asks, then takes its hits and its synced copy`() = screen { ship ->
        onNodeWithText("Add a watchword").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Add a watchword…") }
        onNode(hasSetTextAction()).performTextInput("mars")
        onNodeWithText("Add").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("lunch on mars") }
        onAllNodesWithContentDescription("Delete")[0].performClick()
        waitUntil(timeoutMillis = 5_000) { shows("This clears its 1 hit.") }
        onNodeWithText("Delete").performClick()
        waitUntil(timeoutMillis = 5_000) { ship.pokesTo("settings").any { "del-entry" in it.json.toString() && "mars" in it.json.toString() } }
        waitUntil(timeoutMillis = 5_000) { !shows("lunch on mars") }
    }
}
