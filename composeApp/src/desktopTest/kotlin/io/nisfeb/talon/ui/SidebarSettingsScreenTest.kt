package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.ui.screens.SidebarSettingsScreen
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.FakeAiSettings
import io.nisfeb.talon.urbit.FakeShip
import io.nisfeb.talon.urbit.SettingsSyncImpl
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What the sidebar holds: an item hidden is kept here and mirrored to
 * the ship, and Chats never hides. Reordering is not covered: the
 * reorder library's handle does not answer the harness's injected drags.
 */
@OptIn(ExperimentalTestApi::class)
class SidebarSettingsScreenTest {
    private val ui = InMemoryUiSettings()
    private val ship = FakeShip("~zod")

    private fun sidebar(synced: Boolean = true, block: ComposeUiTest.(AppDatabase) -> Unit) {
        val tmp = createTempDirectory(prefix = "talon-sidebar-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val sync = if (synced) SettingsSyncImpl(db = db, aiSettings = FakeAiSettings()).apply { attach(ship.channel) } else null
        val repo = TlonChatRepo(db, settingsSync = sync).apply { attachForTest(ship.channel, "~zod") }
        try {
            runComposeUiTest {
                setContent { TalonTheme(darkTheme = false) { SidebarSettingsScreen(repo = repo, uiSettings = ui, onBack = {}) } }
                waitUntil(timeoutMillis = 5_000) { shows("Chats") }
                block(db)
            }
        } finally {
            db.close()
            tmp.deleteRecursively()
        }
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** The switch on [label]'s row: the one level with it. */
    private fun ComposeUiTest.switchOf(label: String) {
        val y = onAllNodesWithText(label)[0].fetchSemanticsNode().boundsInRoot.center.y
        val switches = onAllNodes(isToggleable())
        switches[switches.fetchSemanticsNodes().indices.minBy { kotlin.math.abs(switches[it].fetchSemanticsNode().boundsInRoot.center.y - y) }].performClick()
    }

    @Test
    fun `Chats is always there, and an item hidden is kept and told to the ship`() = sidebar { db ->
        assertTrue(shows("Always on the sidebar") && shows("On"))
        switchOf("Mail")
        waitUntil(timeoutMillis = 5_000) { runBlocking { db.railItemPrefs().streamAll().first() }.any { it.itemName == "Mail" && !it.visible } }
        waitUntil(timeoutMillis = 5_000) { ship.pokesTo("settings").isNotEmpty() }
        val put = ship.pokesTo("settings").single().json.toString()
        assertTrue("rail-items" in put && "Mail" in put, put)
    }

    @Test
    fun `with no settings sync, a change says it was not saved`() = sidebar(synced = false) {
        switchOf("Mail")
        waitUntil(timeoutMillis = 5_000) { shows("Couldn't save — settings sync unavailable.") }
    }
}
