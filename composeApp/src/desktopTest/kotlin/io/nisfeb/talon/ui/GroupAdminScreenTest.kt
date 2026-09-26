package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import kotlin.test.assertEquals
import kotlinx.coroutines.launch
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
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
import io.nisfeb.talon.call.CallController
import io.nisfeb.talon.call.CallEngineProvider
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.ui.screens.GroupAdminScreen
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.FakeShip
import io.nisfeb.talon.urbit.TlonChatRepo
import io.nisfeb.talon.urbit.UrbitSession
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

    /** [rooms], when given, is %trunk's list of lines this ship hosts, and turns calling on. */
    private fun admin(me: String = "~zod", rooms: String? = null, group: String = record, block: ComposeUiTest.(FakeShip) -> Unit) {
        val tmp = createTempDirectory(prefix = "talon-admin-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val ship = FakeShip("~zod").apply {
            scries["groups/v2/groups/$flag"] = group
            scries["trunk/version"] = """{"wire":9}"""
            scries["trunk/policy"] = "{}"
            scries["trunk/sfu"] = """{"base":"https://sfu.zod.test","configured":"true"}"""
            scries["trunk/rooms"] = rooms ?: "[]"
            scries["trunk/lines"] = "[]"
        }
        val repo = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod"); notes.attach(ship.channel) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ship.channel.events().launchIn(scope)
        val calls = rooms?.let {
            CallController(UrbitSession(ship.http, ship.session).apply { tryRestore("~zod") }, CallEngineProvider { error("no media") })
                .apply { start() }
        }
        try {
            runComposeUiTest {
                calls?.let { c -> waitUntil(timeoutMillis = 10_000) { c.wire.value == 9 && c.shipSfuBase.value.isNotEmpty() } }
                setContent {
                    TalonTheme(darkTheme = false) {
                        GroupAdminScreen(db = db, repo = repo, flag = flag, onBack = {}, me = me, callController = calls)
                    }
                }
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("The Garden", substring = true).fetchSemanticsNodes().isNotEmpty() }
                block(ship)
            }
        } finally {
            calls?.stop()
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

    // ─── channels ─────────────────────────────────────────────

    /** The group with its roles and one channel, General, open to every member. */
    private val withChannels = record.trimEnd().removeSuffix("}") + """,
        "roles":{"admin":{"meta":{"title":"Admin","description":"","image":"","cover":""}},
                 "gardener":{"meta":{"title":"Gardeners","description":"","image":"","cover":""}}},
        "channels":{"chat/~zod/general":{"meta":{"title":"General","description":"talk","image":"","cover":""},
                    "added":1700000000000,"section":"default","readers":[],"join":true}}}"""

    private val nest = "chat/~zod/general"

    /** Open General's settings; [writers] is what this ship's %channels says of who may post, null for nothing. */
    private fun ComposeUiTest.openGeneral(ship: FakeShip, writers: String? = "[]") {
        writers?.let { ship.scries["channels/v4/$nest/perm"] = """{"writers":$it,"group":"$flag"}""" }
        onNodeWithText("Settings").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Who can post") }
        waitUntil(timeoutMillis = 5_000) { !shows("Asking your ship…") }
    }

    private fun ComposeUiTest.toggle(label: String) = onNode(isToggleable() and hasText(label))

    /** Toggle [label]'s switch or box and wait for the poke to [app] it sends. */
    private fun ComposeUiTest.flip(ship: FakeShip, label: String, app: String): FakeShip.Poke {
        val before = ship.pokesTo(app).size
        toggle(label).performClick()
        waitUntil(timeoutMillis = 5_000) { ship.pokesTo(app).size > before }
        return ship.pokesTo(app).last()
    }

    private fun ComposeUiTest.says(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `an admin decides who can post in a channel, a role at a time`() = admin(group = withChannels) { ship ->
        openGeneral(ship)
        val only = flip(ship, "Everyone in the group can post", "channels")
        assertEquals("channel-action-2", only.mark)
        assertEquals("""{"channel":{"nest":"$nest","action":{"add-writers":["admin"]}}}""", only.json.toString())
        waitUntil(timeoutMillis = 5_000) { shows("Gardeners") }
        assertEquals("""{"channel":{"nest":"$nest","action":{"add-writers":["gardener"]}}}""", flip(ship, "Gardeners", "channels").json.toString())
        assertEquals("""{"channel":{"nest":"$nest","action":{"del-writers":["admin"]}}}""", flip(ship, "Admin", "channels").json.toString())
        // The last role left cannot be unticked: none is everyone.
        toggle("Gardeners").assertIsNotEnabled()
        assertEquals("""{"channel":{"nest":"$nest","action":{"del-writers":["gardener"]}}}""", flip(ship, "Everyone in the group can post", "channels").json.toString())
    }

    @Test
    fun `who can read is the group's, sent to it`() = admin(group = withChannels) { ship ->
        openGeneral(ship)
        assertEquals(
            """{"group":{"flag":"$flag","a-group":{"channel":{"nest":"$nest","a-channel":{"add-readers":["admin"]}}}}}""",
            flip(ship, "Everyone in the group can read", "groups").json.toString(),
        )
    }

    @Test
    fun `a new title goes with the rest of the channel as the group had it`() = admin(group = withChannels) { ship ->
        openGeneral(ship)
        onNode(hasSetTextAction() and hasText("General")).performTextReplacement("Chat")
        val saved = pressFor(ship, "Save title and description")
        assertEquals(
            """{"group":{"flag":"$flag","a-group":{"channel":{"nest":"$nest","a-channel":{"edit":{"meta":{"title":"Chat","description":"talk","image":"","cover":""},"added":1700000000000,"section":"default","readers":[],"join":true}}}}}}""",
            saved,
        )
    }

    @Test
    fun `a channel is deleted only once that is confirmed`() = admin(group = withChannels) { ship ->
        openGeneral(ship)
        onNodeWithText("Delete channel").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Delete General?") }
        onNodeWithText("Cancel").performClick()
        assertTrue(ship.pokesTo("groups").none { "del" in it.json.toString() })
        onNodeWithText("Delete channel").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Delete General?") }
        assertEquals(
            """{"group":{"flag":"$flag","a-group":{"channel":{"nest":"$nest","a-channel":{"del":null}}}}}""",
            pressFor(ship, "Delete"),
        )
    }

    @Test
    fun `a channel this ship has not joined says so, and posting is not offered`() = admin(group = withChannels) { ship ->
        openGeneral(ship, writers = null) // %channels has no such channel here: 404
        assertTrue(says("hasn't joined this channel"))
        assertTrue(onAllNodes(isToggleable() and hasText("Everyone in the group can post")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a ship that fails to say who can post says so, not that it has not joined`() = admin(group = withChannels) { ship ->
        ship.failScry = { it.endsWith("/perm") }
        openGeneral(ship, writers = null)
        assertTrue(says("didn't say who can post"))
        assertTrue(!says("hasn't joined"))
        assertTrue(onAllNodes(isToggleable() and hasText("Everyone in the group can post")).fetchSemanticsNodes().isEmpty())
    }

    // A notebook is %notes', not a %channels channel: asking %channels
    // who may post in one always failed, and said to join a notebook the
    // owner had written in that day.
    @Test
    fun `a notebook says everyone who reads it writes in it, and its readers are still set`() = admin(
        group = record.trimEnd().removeSuffix("}") + """,
            "roles":{"admin":{"meta":{"title":"Admin","description":"","image":"","cover":""}}},
            "channels":{"notes/~zod/journal":{"meta":{"title":"Journal","description":"","image":"","cover":""},
                        "added":1700000000000,"section":"default","readers":[],"join":true}}}""",
    ) { ship ->
        onNodeWithText("Settings").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Who can post") }
        assertTrue(says("everyone who can read it can also write in it"))
        assertTrue(ship.scried.none { it.startsWith("channels/") }, ship.scried.toString())
        assertTrue(onAllNodes(isToggleable() and hasText("Everyone in the group can post")).fetchSemanticsNodes().isEmpty())
        assertEquals(
            """{"group":{"flag":"$flag","a-group":{"channel":{"nest":"notes/~zod/journal","a-channel":{"add-readers":["admin"]}}}}}""",
            flip(ship, "Everyone in the group can read", "groups").json.toString(),
        )
    }

    @Test
    fun `a member who is no admin is shown no channel settings`() = admin(me = "~nec", group = withChannels) {
        assertTrue(!shows("Channels") && !shows("Settings"))
    }

    @Test
    fun `a change the ship refuses is said`() = admin(group = withChannels) { ship ->
        ship.refuse = { if (it.app == "channels") "not an admin" else null }
        openGeneral(ship)
        toggle("Everyone in the group can post").performClick()
        waitUntil(timeoutMillis = 5_000) { says("Your ship did not take it") }
    }

    @Test
    fun `a change of roles adds before it takes away, and finishes when the screen goes`() = runBlocking {
        val tmp = createTempDirectory(prefix = "talon-admin-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val ship = FakeShip("~zod")
        val repo = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod") }
        val events = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ship.channel.events().launchIn(events)
        try {
            // A busy ship: each poke takes a while to be answered.
            ship.refuse = { Thread.sleep(300); null }
            val screen = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            screen.launch { repo.setChannelWriters(nest, setOf("admin"), setOf("gardener")) }
            kotlinx.coroutines.delay(100)
            screen.cancel()
            kotlinx.coroutines.withTimeout(10_000) { while (ship.pokesTo("channels").size < 2) kotlinx.coroutines.delay(20) }
            assertEquals(
                listOf("add-writers", "del-writers"),
                ship.pokesTo("channels").map { it.json.toString().substringAfter("\"action\":{\"").substringBefore('"') },
                "never open to everyone in between",
            )
        } finally {
            events.cancel()
            db.close()
            tmp.deleteRecursively()
        }
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
    fun `an approval the ship refuses says why, and the request is still there`() = admin { ship ->
        ship.refuse = { if (it.app == "groups") "not allowed" else null }
        onAllNodesWithText("Accept")[0].performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("not allowed", substring = true).fetchSemanticsNodes().isNotEmpty() }
        waitUntil(timeoutMillis = 5_000) { shows("~wicrys-bortel") }
    }

    @Test
    fun `an unban the ship refuses says why, and the ban stands`() = admin { ship ->
        ship.refuse = { if (it.app == "groups") "not allowed" else null }
        onAllNodesWithText("Unban")[0].performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("not allowed", substring = true).fetchSemanticsNodes().isNotEmpty() }
        waitUntil(timeoutMillis = 5_000) { shows("~dopzod") }
    }

    @Test
    fun `a refusal outlasts the next action's refresh, until dismissed`() = admin { ship ->
        ship.refuse = { if (it.app == "groups") "not allowed" else null }
        onAllNodesWithText("Accept")[0].performScrollTo().performClick()
        val refused = { onAllNodesWithText("not allowed", substring = true).fetchSemanticsNodes().isNotEmpty() }
        waitUntil(timeoutMillis = 5_000, condition = refused)
        ship.refuse = { null }
        pressFor(ship, "Unban")
        val reads = ship.scried.size
        waitUntil(timeoutMillis = 5_000) { ship.scried.size > reads }
        waitForIdle()
        assertTrue(refused(), "the unban's refresh is not an answer to the approval")
        onNodeWithText("Dismiss").performClick()
        waitUntil(timeoutMillis = 5_000) { !refused() }
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

    // ─── the group's party line ────────────────────────────────────

    /** A line this ship hosts for the group, as %trunk lists it. */
    private fun line(listen: Boolean = false, title: String = "The Garden", sfu: String = "", bound: Boolean = false) =
        """[{"name":"garden","title":"$title","listen":$listen,"sfu-base":"$sfu","custom-sfu":${sfu.isNotEmpty()},"members":["~zod","~nec"],"admins":["~zod"]""" +
            (if (bound) ""","group":{"ship":"~zod","name":"garden"}""" else "") + "}]"

    private fun ComposeUiTest.switchOf(label: String) {
        onAllNodesWithText(label)[0].performScrollTo()
        val y = onAllNodesWithText(label)[0].fetchSemanticsNode().boundsInRoot.center.y
        val switches = onAllNodes(isToggleable())
        switches[switches.fetchSemanticsNodes().indices.minBy { kotlin.math.abs(switches[it].fetchSemanticsNode().boundsInRoot.center.y - y) }].performClick()
    }

    private fun ComposeUiTest.trunkPoke(ship: FakeShip, containing: String): String {
        waitUntil(timeoutMillis = 5_000) { ship.pokesTo("trunk").any { containing in it.json.toString() } }
        return ship.pokesTo("trunk").last { containing in it.json.toString() }.json.toString()
    }

    @Test
    fun `the group's party line is switched on with its people`() = admin(rooms = "[]") {
        waitUntil(timeoutMillis = 5_000) { shows("A voice room for the whole group. Every channel joins the same one.") }
        switchOf("A voice room for the whole group. Every channel joins the same one.")
        val on = trunkPoke(it, "configure-room")
        for (part in listOf("\"host\":\"~zod\"", "\"name\":\"garden\"", "\"open\":true", "\"title\":\"The Garden\"", "\"~nec\"")) {
            assertTrue(part in on, "$part in $on")
        }
    }

    @Test
    fun `a line is opened to listeners, and its topic set`() = admin(rooms = line()) {
        waitUntil(timeoutMillis = 5_000) { shows("Only group members can join.") }
        switchOf("Only group members can join.")
        assertTrue("\"listen\":true" in trunkPoke(it, "\"listen\":true"))
        onNode(hasSetTextAction() and hasText("Topic")).performScrollTo().performTextReplacement("Seed swap tonight")
        onNodeWithText("Set").performClick()
        assertTrue("Seed swap tonight" in trunkPoke(it, "Seed swap tonight"))
    }

    @Test
    fun `listening on, a link is asked for`() = admin(rooms = line(listen = true)) {
        waitUntil(timeoutMillis = 5_000) { shows("Create listen link") }
        assertTrue(shows("A link expires on its own and can't be revoked early."))
        onNodeWithText("Create listen link").performScrollTo().performClick()
        assertTrue("\"name\":\"garden\"" in trunkPoke(it, "share-room"))
    }

    @Test
    fun `the group chooses its own server, and can go back to the host's`() = admin(rooms = line(sfu = "https://ours.test")) {
        waitUntil(timeoutMillis = 5_000) { shows("https://ours.test · chosen by this group") }
        onNodeWithContentDescription("Server settings").performScrollTo().performClick()
        val base = onNode(hasSetTextAction() and hasText("https://your-sidecar"))
        base.performScrollTo().performTextInput("https://mine.test/")
        onNodeWithText("Use this server").assertIsNotEnabled()
        onNode(hasSetTextAction() and hasText("Shared secret")).performTextInput("s3cret")
        onNodeWithText("Use this server").performScrollTo().performClick()
        val chosen = trunkPoke(it, "https://mine.test")
        assertTrue("\"base\":\"https://mine.test\"" in chosen && "\"key\":\"s3cret\"" in chosen, chosen)

        onNodeWithContentDescription("Server settings").performScrollTo().performClick()
        onNodeWithText("Use the host's").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { it.pokesTo("trunk").any { p -> "\"sfu\":null" in p.json.toString() && "\"keep-sfu\":false" in p.json.toString() } }
    }

    @Test
    fun `a member who is not an admin is only told whether there is a line`() = admin(me = "~nec", rooms = line()) {
        waitUntil(timeoutMillis = 5_000) { shows("This group has a party line.") }
        assertTrue(!shows("Anyone with a link can listen"))
    }

    @Test
    fun `a line's gates are asked of its host, then set, and a muted ship unmuted`() = admin(
        rooms = line(bound = true),
        group = record.replaceFirst("\"admins\":", "\"roles\":{\"gardener\":{\"meta\":{\"title\":\"Gardener\"}}},\"admins\":"),
    ) { ship ->
        waitUntil(timeoutMillis = 5_000) { shows("Asking the host who may join and speak…") }
        trunkPoke(ship, "get-room-access")
        // The host's answer, on the controller's /calls subscription.
        runBlocking { ship.emit("""{"id":1,"response":"diff","json":{"access-state":{"from":"~zod","name":"garden","join":null,"speak":["gardener"],"muted":["~bus"]}}}""") }
        waitUntil(timeoutMillis = 5_000) { shows("Who can join") && shows("Muted on the line") }
        // Speaking is for gardeners already; joining opens to them too.
        onAllNodesWithText("Only these roles")[0].performScrollTo().performClick()
        val set = trunkPoke(ship, "set-room-access")
        assertTrue("\"join\":[]" in set && "\"speak\":[\"gardener\"]" in set, set)
        onNodeWithText("Unmute").performScrollTo().performClick()
        val unmute = trunkPoke(ship, "moderate-member")
        assertTrue("\"who\":\"~bus\"" in unmute && "\"mute\":false" in unmute, unmute)
    }
}
