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
        "~nec/garden":{"invites":[{"from":"~nec","valid":true}],
          "preview":{"meta":{"title":"The Garden","description":"growing things","image":"#336699","cover":""},"member-count":12,"privacy":"private"}},
        "~bus/lapsed":{"invites":[{"from":"~bus","valid":false}],"preview":{"meta":{"title":"Lapsed"}}}}}"""

    private fun invites(prepare: FakeShip.() -> Unit = {}, block: ComposeUiTest.(FakeShip) -> Unit) =
        invitesWith(prepare) { ship, _ -> block(ship) }

    private fun invitesWith(prepare: FakeShip.() -> Unit = {}, block: ComposeUiTest.(FakeShip, TlonChatRepo) -> Unit) {
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
                waitUntil(timeoutMillis = 5_000) { repo.invitesFlow.value != null || shows("Couldn't load invites") }
                block(ship, repo)
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
    fun `accepting joins the group, and it waits as joining, not as an invite`() = invites { ship ->
        onNodeWithText("Accept").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Waiting for ~nec, which hosts the group, to answer your ship.") }
        assertTrue(onAllNodesWithText("Accept").fetchSemanticsNodes().isEmpty(), "nothing left to accept")
        val join = ship.pokesTo("groups").first { it.mark == "group-join" }.json.toString()
        assertTrue("\"flag\":\"~nec/garden\"" in join, join)
    }

    /** The Garden, accepted, with the ship still waiting on its host. */
    private val joining = """{"groups":{},"foreigns":{
        "~nec/garden":{"invites":[{"from":"~nec","valid":true}],"progress":"join",
          "preview":{"meta":{"title":"The Garden"},"member-count":12,"privacy":"private"}}}}"""

    // The invite stays valid on the ship while it waits, and %groups does
    // nothing with another join then. Listed as an invite it came back
    // after every refresh, was announced again, and was accepted again.
    @Test
    fun `a join the host has not answered is joining, not an invite, and is not announced again`() = invitesWith(
        prepare = { scries["groups-ui/v7/init"] = joining },
    ) { ship, repo ->
        assertTrue(shows("Joining") && shows("Waiting for ~nec, which hosts the group, to answer your ship."))
        assertTrue(onAllNodesWithText("Accept").fetchSemanticsNodes().isEmpty())
        assertTrue(repo.invitesFlow.value.orEmpty().isEmpty(), "nothing waits on an answer, so no badge counts it")
        val told = java.util.concurrent.CopyOnWriteArrayList<String>()
        repo.groupInviteListener = { told += it.flag }
        runBlocking { repo.refreshInvites(notify = true) }
        assertTrue(told.isEmpty(), "announced again: $told")

        // Stopped, the ship clears the join and the invite is back to answer.
        ship.scries["groups-ui/v7/init"] = init
        onNodeWithText("Stop joining").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Accept") }
        val cancel = ship.pokesTo("groups").single { it.mark == "group-cancel" }
        assertTrue(cancel.json.toString() == "\"~nec/garden\"", cancel.toString())
        assertTrue(!shows("Waiting for ~nec"))
    }

    // The ship's own answer (ricsul, 2026-09-29) for a join whose host
    // never acked it: invited by one ship, hosted on another, offline.
    @Test
    fun `a join the host never answered says who invited, who hosts, and why it waits`() = invites(
        prepare = {
            scries["groups-ui/v7/init"] = """{"groups":{},"foreigns":{
                "~sarhut/sovereign":{"invites":[{"flag":"~sarhut/sovereign","token":null,"from":"~tansyr-tilbus","note":null,"valid":true}],
                  "lookup":null,"progress":"join","token":null,"preview":{"meta":{"title":"Urbit Curious"}}},
                "~nec/garden":{"invites":[{"from":"~nec","valid":true}],"progress":"watch","preview":{"meta":{"title":"The Garden"}}}}}"""
        },
    ) {
        waitUntil(timeoutMillis = 5_000) { shows("Urbit Curious") }
        assertTrue(shows("from ~tansyr-tilbus") && shows("~sarhut is likely offline"))
        assertTrue(shows("~nec let your ship in. Getting the group.") && !shows("~nec is likely offline"))
    }

    @Test
    fun `a group already joined is not an invite, and a failed join says so`() = invites(
        prepare = {
            scries["groups-ui/v7/init"] = """{"groups":{"~nec/garden":{}},"foreigns":{
                "~nec/garden":{"invites":[{"from":"~nec","valid":true}],"progress":null,"preview":{"meta":{"title":"The Garden"}}},
                "~bus/stuck":{"invites":[{"from":"~bus","valid":true}],"progress":"error","preview":{"meta":{"title":"Stuck"}}}}}"""
        },
    ) {
        waitUntil(timeoutMillis = 5_000) { shows("Stuck") }
        assertTrue(!shows("The Garden"), "we are in it")
        assertTrue(shows("Joining it failed last time. Accept tries again."))
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

    @Test
    fun `invites the ship will not give say so rather than spinning`() = invites(prepare = { scries.remove("groups-ui/v7/init") }) {
        waitUntil(timeoutMillis = 5_000) { shows("Couldn't load invites") }
        assertTrue(!shows("No pending invites."), "no answer is not no invites")
    }

    @Test
    fun `a refresh with no answer keeps the invites shown, and says it could not refresh`() = invites { ship ->
        ship.scries.remove("groups-ui/v7/init")
        onNodeWithContentDescription("Refresh").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Couldn't refresh") }
        assertTrue(shows("The Garden"), "the invite is still there")
    }
}
