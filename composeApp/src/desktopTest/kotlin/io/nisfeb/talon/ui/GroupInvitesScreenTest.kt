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
import io.nisfeb.talon.ui.screens.GroupInvitesScreen
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
import kotlin.test.assertTrue

/**
 * Group invites waiting for an answer: what each says of the group, and
 * accepting or declining it on the ship.
 */
@OptIn(ExperimentalTestApi::class)
class GroupInvitesScreenTest {
    private val init = """{"foreigns":{
        "~nec/garden":{"invites":[{"ship":"~nec","valid":true}],
          "preview":{"meta":{"title":"The Garden","description":"growing things","image":"#336699","cover":""},"member-count":12,"privacy":"private"}},
        "~bus/lapsed":{"invites":[{"ship":"~bus","valid":false}],"preview":{"meta":{"title":"Lapsed"}}}}}"""

    private fun invites(prepare: FakeShip.() -> Unit = {}, block: ComposeUiTest.(FakeShip) -> Unit) {
        val tmp = createTempDirectory(prefix = "talon-invites-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val ship = FakeShip("~zod").apply { scries["groups-ui/v7/init"] = init }.apply(prepare)
        val repo = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ship.channel.events().launchIn(scope)
        try {
            runComposeUiTest {
                setContent { TalonTheme(darkTheme = false) { GroupInvitesScreen(repo = repo, onBack = {}) } }
                waitUntil(timeoutMillis = 5_000) { shows("The Garden") || shows("No pending invites.") }
                block(ship)
            }
        } finally {
            runBlocking { repo.stopAndJoinForTest() }
            scope.cancel()
            db.close()
            tmp.deleteRecursively()
        }
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `an invite says who sent it, what the group is, and how big`() = invites {
        assertTrue(shows("from ~nec") && shows("growing things") && shows("12 members · Private"))
        assertTrue(!shows("Lapsed"), "an invite no longer valid is not offered")
    }

    @Test
    fun `accepting joins the group and the invite goes`() = invites { ship ->
        onNodeWithText("Accept").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("No pending invites.") }
        val join = ship.pokesTo("groups").first { it.mark == "group-join" }.json.toString()
        assertTrue("\"flag\":\"~nec/garden\"" in join, join)
    }

    @Test
    fun `declining tells the ship and the invite goes`() = invites { ship ->
        onNodeWithText("Reject").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("No pending invites.") }
        val no = ship.pokesTo("groups").single()
        assertTrue(no.mark == "invite-decline" && "~nec/garden" in no.json.toString(), no.toString())
    }

    @Test
    fun `a join the ship refuses says so, and the invite stays`() = invites(prepare = { refuse = { if (it.mark == "group-join") "group is full" else null } }) {
        onNodeWithText("Accept").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Couldn't join") }
        assertTrue(shows("The Garden") && !shows("Couldn't refresh"), "not dressed up as a refresh failure")
    }

    @Test
    fun `nothing waiting says so, and a refresh asks again`() = invites(prepare = { scries["groups-ui/v7/init"] = """{"foreigns":{}}""" }) { ship ->
        assertTrue(shows("No pending invites."))
        val asked = ship.scried.count { it == "groups-ui/v7/init" }
        onNodeWithContentDescription("Refresh").performClick()
        waitUntil(timeoutMillis = 5_000) { ship.scried.count { it == "groups-ui/v7/init" } > asked }
    }
}
