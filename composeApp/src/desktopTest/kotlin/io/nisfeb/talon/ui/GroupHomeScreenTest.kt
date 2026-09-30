package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.ChannelGroupEntity
import io.nisfeb.talon.data.GroupEntity
import io.nisfeb.talon.ui.screens.GroupHomeScreen
import io.nisfeb.talon.ui.theme.TalonTheme
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A group's own page: its channels for a member, a way in for someone
 * who is not, and leaving, each checked by what reaches the ship.
 */
@OptIn(ExperimentalTestApi::class)
class GroupHomeScreenTest {
    private val flag = "~bus/crew"
    private val did: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    private fun home(member: Boolean = true, channels: Boolean = true, block: ComposeUiTest.(FakeShip, AppDatabase) -> Unit) {
        val tmp = createTempDirectory(prefix = "talon-ghome-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val ship = FakeShip("~zod")
        val repo = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod") }
        runBlocking {
            if (member) db.groups().upsertGroups(listOf(GroupEntity(flag, "The Crew", null)))
            if (member && channels) db.groups().upsertChannelGroups(listOf(
                ChannelGroupEntity("chat/~bus/general", flag, title = "General"),
                ChannelGroupEntity("diary/~bus/notes", flag),
            ))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ship.channel.events().launchIn(scope)
        try {
            runComposeUiTest {
                setContent {
                    TalonTheme(darkTheme = false) {
                        GroupHomeScreen(db = db, repo = repo, flag = flag, onBack = { did += "back" }, onOpenChannel = { did += "open $it" })
                    }
                }
                block(ship, db)
            }
        } finally {
            scope.cancel()
            db.close()
            tmp.deleteRecursively()
        }
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun ComposeUiTest.showing(text: String) = waitUntil(timeoutMillis = 5_000) { shows(text) }

    @Test
    fun `a member sees the channels, named or by their slug, and opens one`() = home { _, _ ->
        showing("The Crew")
        assertTrue(shows("General") && shows("#notes"))
        onNodeWithText("General").performClick()
        assertEquals(listOf("open chat/~bus/general"), did)
    }

    @Test
    fun `a group with no channels says so`() = home(channels = false) { _, _ ->
        showing("No channels in this group yet.")
    }

    @Test
    fun `leaving asks, goes once the ship agrees, and returns`() = home { ship, db ->
        showing("The Crew")
        onNodeWithContentDescription("More").performClick()
        onNodeWithText("Leave group").performClick()
        showing("Leave The Crew?")
        onNodeWithText("Leave").performClick()
        waitUntil(timeoutMillis = 5_000) { did == listOf("back") }
        assertEquals("group-leave", ship.pokesTo("groups").single().mark)
        assertEquals(null, runBlocking { db.groups().getGroup(flag) })
    }

    @Test
    fun `a leave the ship refuses says why and stays`() = home { ship, db ->
        ship.refuse = { if (it.mark == "group-leave") "not now" else null }
        showing("The Crew")
        onNodeWithContentDescription("More").performClick()
        onNodeWithText("Leave group").performClick()
        onNodeWithText("Leave").performClick()
        showing("not now")
        assertTrue(did.isEmpty())
        assertNotNull(runBlocking { db.groups().getGroup(flag) })
    }

    @Test
    fun `someone not in the group asks to join, and is told what happens next`() = home(member = false) { ship, _ ->
        showing("You haven't joined this group.")
        assertTrue(shows(flag), "named by its flag until joined")
        onNodeWithText("Join group").performClick()
        showing("Requested to join.")
        assertEquals("group-knock", ship.pokesTo("groups").single().mark)
    }

    @Test
    fun `a refused knock says why and can be tried again`() = home(member = false) { ship, _ ->
        ship.refuse = { if (it.mark == "group-knock") "banned" else null }
        showing("Join group")
        onNodeWithText("Join group").performClick()
        showing("banned")
        assertTrue(!shows("Requested to join."))
    }
}
