package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.call.CallController
import io.nisfeb.talon.call.CallEngineProvider
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.ChannelGroupEntity
import io.nisfeb.talon.data.GroupEntity
import io.nisfeb.talon.ui.screens.PartyLinesList
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.FakeShip
import io.nisfeb.talon.urbit.UrbitSession
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every party line across our groups: each by its group's name, how many
 * are on it as the host says, and a tap that lands in the group's channel
 * to join; none known says who turns one on.
 */
@OptIn(ExperimentalTestApi::class)
class PartyLinesListTest {
    private val opened = CopyOnWriteArrayList<String>()
    private val contacts = ContactMap(
        groups = listOf(GroupEntity("~zod/garden", "The Garden", null)),
        channelGroups = listOf(ChannelGroupEntity("chat/~zod/garden", "~zod/garden")),
    )

    private fun lines(rooms: String, block: ComposeUiTest.(FakeShip) -> Unit) {
        val tmp = createTempDirectory(prefix = "talon-lines-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val ship = FakeShip("~zod").apply {
            scries["trunk/version"] = """{"wire":9}"""
            scries["trunk/policy"] = "{}"
            scries["trunk/rooms"] = rooms
            scries["trunk/lines"] = "[]"
        }
        val calls = CallController(UrbitSession(ship.http, ship.session).apply { tryRestore("~zod") }, CallEngineProvider { error("no media") })
            .apply { start() }
        try {
            runComposeUiTest {
                waitUntil(timeoutMillis = 10_000) { calls.wire.value == 9 }
                setContent {
                    TalonTheme(darkTheme = false) {
                        PartyLinesList(db = db, callController = calls, partyLine = null, contacts = contacts, onOpenLine = { opened += it })
                    }
                }
                block(ship)
            }
        } finally {
            calls.stop()
            db.close()
            tmp.deleteRecursively()
        }
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a group's line shows by the group's name, counts who is on, and opens its channel`() =
        lines("""[{"name":"garden","title":"garden","listen":false,"members":["~zod"],"admins":["~zod"]}]""") { ship ->
            waitUntil(timeoutMillis = 5_000) { shows("The Garden") }
            assertTrue(shows("Nobody is on the party line right now"))
            runBlocking { ship.emit("""{"id":1,"response":"diff","json":{"present":{"from":"~zod","name":"garden","n":3}}}""") }
            waitUntil(timeoutMillis = 5_000) { shows("3 on the party line") }
            onNodeWithText("The Garden").performClick()
            assertEquals(listOf("chat/~zod/garden"), opened.toList())
        }

    @Test
    fun `with no lines anywhere it says who turns one on`() = lines("[]") {
        waitUntil(timeoutMillis = 5_000) { shows("A group admin turns one on from the group's info page.") }
    }
}
