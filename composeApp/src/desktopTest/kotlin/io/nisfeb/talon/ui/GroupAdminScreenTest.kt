package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.ui.screens.GroupAdminScreen
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.FakeShip
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Running a group we host: what the admin screen shows from the ship's
 * own record of the group, and the poke each action sends.
 */
@OptIn(ExperimentalTestApi::class)
class GroupAdminScreenTest {
    private val flag = "~zod/garden"

    /** The group as %groups returns it: us hosting, a member, a ban, an invite and a request. */
    private val record = """{
        "meta":{"title":"The Garden","description":"growing things","image":"","cover":""},
        "admins":["admin"],
        "seats":{"~zod":{"roles":[],"joined":0},"~nec":{"roles":[],"joined":0}},
        "admissions":{"privacy":"private",
          "banned":{"ships":["~dopzod"],"ranks":[]},
          "invited":{"~mastyr-bottec":{"token":"0v2.abc","at":"~2026"}},
          "pending":{"~rallec-tadpur":[]},
          "requests":{"~wicrys-bortel":{"requestedAt":0}}}}"""

    private fun admin(block: ComposeUiTest.(FakeShip) -> Unit) {
        val tmp = createTempDirectory(prefix = "talon-admin-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val ship = FakeShip("~zod").apply { scries["groups/v2/groups/$flag"] = record }
        val repo = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod"); notes.attach(ship.channel) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ship.channel.events().launchIn(scope)
        try {
            runComposeUiTest {
                setContent {
                    TalonTheme(darkTheme = false) {
                        GroupAdminScreen(db = db, repo = repo, flag = flag, onBack = {}, me = "~zod")
                    }
                }
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("The Garden", substring = true).fetchSemanticsNodes().isNotEmpty() }
                block(ship)
            }
        } finally {
            scope.cancel()
            db.close()
            tmp.deleteRecursively()
        }
    }

    private fun ComposeUiTest.shows(text: String) =
        onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** Tap the [n]th node reading [text], and wait for the poke to [app] it sends. */
    private fun ComposeUiTest.pressFor(ship: FakeShip, text: String, n: Int = 0, app: String = "groups"): String {
        val before = ship.pokesTo(app).size
        val node = onAllNodesWithText(text)[n]
        runCatching { node.performScrollTo() } // a dialog's buttons have nothing to scroll
        node.performClick()
        waitUntil(timeoutMillis = 5_000) { ship.pokesTo(app).size > before }
        return ship.pokesTo(app).last().json.toString()
    }

    @Test
    fun `the ship's record of the group is laid out in full`() = admin {
        for (t in listOf("Invited · 2", "Requests · 1", "Banned · 1", "Members · 2", "~mastyr-bottec", "~rallec-tadpur", "~wicrys-bortel", "~dopzod")) {
            assertTrue(shows(t), "shows $t")
        }
        assertTrue(shows("Private — members must be invited or request access."))
    }

    @Test
    fun `accepting a request lets them in by name`() = admin { ship ->
        val yes = pressFor(ship, "Accept")
        assertTrue("~wicrys-bortel" in yes && "approve" in yes, yes)
    }

    @Test
    fun `denying a request turns them away by name`() = admin { ship ->
        val no = pressFor(ship, "Deny")
        assertTrue("~wicrys-bortel" in no && "deny" in no, no)
    }

    @Test
    fun `a ban is lifted by name`() = admin { ship ->
        val unban = pressFor(ship, "Unban")
        assertTrue("~dopzod" in unban && "del-ships" in unban, unban)
    }

    @Test
    fun `revoking takes back a link invite by its token and a direct one by name`() = admin { ship ->
        val token = pressFor(ship, "Revoke", n = 0)
        assertTrue("0v2.abc" in token, token)
        val direct = pressFor(ship, "Revoke") // the first row is gone at once
        assertTrue("~rallec-tadpur" in direct, direct)
    }

    @Test
    fun `saving the metadata sends the new title`() = admin { ship ->
        onNode(hasSetTextAction() and hasText("The Garden")).performTextReplacement("The Orchard")
        val meta = pressFor(ship, "Save metadata")
        assertTrue("The Orchard" in meta && "growing things" in meta, meta)
    }

    @Test
    fun `an invite goes to the ship typed`() = admin { ship ->
        onNode(hasSetTextAction() and hasText("~ship, or a word name")).performTextInput("~bus")
        val invite = pressFor(ship, "Invite", n = 1)
        assertTrue("~bus" in invite, invite)
    }

    private fun ComposeUiTest.memberMenu(ship: String) {
        onAllNodesWithText(ship, substring = true)[0].performScrollTo().performTouchInput { longClick() }
        waitUntil(timeoutMillis = 5_000) { shows("Roles: (none)") }
    }

    @Test
    fun `a member's menu makes them admin`() = admin { ship ->
        memberMenu("~nec")
        val role = pressFor(ship, "Make admin")
        assertTrue("~nec" in role && "admin" in role, role)
    }

    @Test
    fun `kicking and banning each ask first, and cancelling sends nothing`() = admin { ship ->
        memberMenu("~nec")
        onNodeWithText("Kick").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Kick ~nec?") }
        onNodeWithText("Cancel").performClick()
        waitForIdle()
        assertTrue(ship.pokesTo("groups").isEmpty(), "cancelled")

        memberMenu("~nec")
        onNodeWithText("Kick").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Kick ~nec?") }
        val kick = pressFor(ship, "Kick")
        assertTrue("~nec" in kick, kick)

        memberMenu("~nec")
        onNodeWithText("Ban").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Ban ~nec?") }
        val ban = pressFor(ship, "Ban")
        assertTrue("~nec" in ban && "add-ships" in ban, ban)
    }

    @Test
    fun `our own row as host offers nothing that can only fail`() = admin {
        onAllNodesWithText("~zod", substring = true).fetchSemanticsNodes().size.let { assertTrue(it > 0) }
        onAllNodesWithText("~zod", substring = true)[0].performScrollTo().performTouchInput { longClick() }
        waitUntil(timeoutMillis = 5_000) { shows("Roles: (none)") }
        for (t in listOf("Make admin", "Kick", "Ban")) assertTrue(!shows(t), "no $t for the host")
    }

    // ─── new channels ─────────────────────────────────────────────

    private fun ComposeUiTest.newChannel(kind: String?, title: String, description: String = "") {
        onNodeWithContentDescription("New channel").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Title").fetchSemanticsNodes().isNotEmpty() }
        kind?.let { onNodeWithText(it).performClick() }
        onAllNodes(hasSetTextAction() and hasText("Title")).onLast().performTextInput(title) // the dialog's, over the group's own
        if (description.isNotEmpty()) onNode(hasSetTextAction() and hasText("Description (optional)")).performTextInput(description)
        onNodeWithText("Create").performClick()
    }

    @Test
    fun `a chat channel is made in the group, with its title and description`() = admin { ship ->
        newChannel(kind = null, title = "Seeds", description = "what to plant")
        waitUntil(timeoutMillis = 5_000) { ship.pokesTo("channels").isNotEmpty() }
        val create = ship.pokesTo("channels").single().json.toString()
        for (part in listOf("\"kind\":\"chat\"", "\"group\":\"$flag\"", "\"title\":\"Seeds\"", "\"description\":\"what to plant\"")) {
            assertTrue(part in create, "$part in $create")
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Description (optional)").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun `a gallery is a heap channel`() = admin { ship ->
        newChannel(kind = "Gallery", title = "Photos")
        waitUntil(timeoutMillis = 5_000) { ship.pokesTo("channels").isNotEmpty() }
        assertTrue("\"kind\":\"heap\"" in ship.pokesTo("channels").single().json.toString())
    }

    @Test
    fun `a notebook is made through the notes app, named by the host`() = admin { ship ->
        ship.answerApi = { method, path, _ ->
            if (method == "POST" && path == "/notes/~/v1/notebooks") """{"body":{"type":"notebook","notebook":{"flagName":"plans-2","host":"~zod","notebook":{"title":"Plans","id":1,"rootFolderId":2},"visibility":"private"}}}""" else null
        }
        newChannel(kind = "Notebook", title = "Plans")
        waitUntil(timeoutMillis = 5_000) { ship.api.any { it.startsWith("POST /notes/~/v1/notebooks") } }
        assertTrue(ship.api.single().contains("\"flagName\":\"garden\""), "made in this group")
        assertTrue(ship.pokesTo("channels").isEmpty())
    }

    @Test
    fun `a channel the ship refuses says why in the dialog, which stays`() = admin { ship ->
        ship.refuse = { if (it.app == "channels") "not an admin" else null }
        newChannel(kind = null, title = "Seeds")
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("not an admin", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodesWithText("Description (optional)").fetchSemanticsNodes().isNotEmpty())
    }
}
