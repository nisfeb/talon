package io.nisfeb.talon.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.ui.screens.GroupAdminScreen
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.Disappearing
import io.nisfeb.talon.urbit.FakeShip
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Disappearing messages as the group's host sets them: the hook pokes to
 * its channels-server, the line in the group's description, the notice
 * in its chats, and what the screen says when the ship does not do it.
 */
@OptIn(ExperimentalTestApi::class)
class DisappearingMessagesTest {
    private val flag = "~zod/garden"
    private val general = "chat/~zod/general"
    private val pics = "heap/~zod/pics"

    /** We host the group, with a chat and a gallery of ours, a chat ~nec hosts and a notebook. */
    private val record = """{
        "meta":{"title":"The Garden","description":"growing things","image":"","cover":""},
        "admins":["admin"],
        "seats":{"~zod":{"roles":[],"joined":0},"~nec":{"roles":["admin"],"joined":0}},
        "admissions":{"privacy":"private","banned":{"ships":[],"ranks":[]},"invited":{},"pending":{},"requests":{}},
        "roles":{"admin":{"meta":{"title":"Admin","description":"","image":"","cover":""}}},
        "channels":{
          "$general":{"meta":{"title":"General","description":"","image":"","cover":""},"added":1,"section":"default","readers":[],"join":true},
          "$pics":{"meta":{"title":"Pics","description":"","image":"","cover":""},"added":2,"section":"default","readers":[],"join":true},
          "chat/~nec/side":{"meta":{"title":"Side","description":"","image":"","cover":""},"added":3,"section":"default","readers":[],"join":true},
          "notes/~zod/journal":{"meta":{"title":"Journal","description":"","image":"","cover":""},"added":4,"section":"default","readers":[],"join":true}}}"""

    /**
     * channels-server's hooks as the ship keeps them: each hook poke
     * changes what the next `/v0/hooks` scry says, and a poke it would
     * crash on is refused.
     */
    private class Hooks(val ship: FakeShip, var compiles: Boolean = true) {
        val id = "0v1.hook"
        var src: String? = null
        val config = mutableMapOf<String, JsonObject>()
        val order = mutableMapOf<String, List<String>>()
        /** Each channel's sweep, and the config it keeps: its own copy, as ho-cron stores it. */
        val crons = mutableMapOf<String, JsonObject>()
        @Volatile var refuse: (String) -> String? = { null }

        fun publish() {
            ship.scries["channels-server/v0/hooks"] = buildJsonObject {
                put("hooks", buildJsonObject {
                    src?.let { s ->
                        put(id, buildJsonObject {
                            put("id", id); put("name", Disappearing.HOOK_NAME); put("src", s); put("compiled", compiles)
                            put("config", buildJsonObject { config.forEach { (n, c) -> put(n, c) } })
                        })
                    }
                })
                put("order", buildJsonObject { order.forEach { (n, seq) -> put(n, buildJsonArray { seq.forEach { add(JsonPrimitive(it)) } }) } })
                put("crons", buildJsonObject {
                    if (crons.isNotEmpty()) put(id, buildJsonObject {
                        crons.forEach { (n, c) -> put(n, buildJsonObject { put("config", c) }) }
                    })
                })
            }.toString()
        }

        fun take(p: FakeShip.Poke): String? {
            val (tag, body) = p.json.jsonObject.entries.single().let { it.key to it.value.jsonObject }
            refuse(tag)?.let { return it }
            fun str(k: String) = body[k]!!.jsonPrimitive.content
            when (tag) {
                "add" -> src = str("src")
                "edit" -> src = str("src")
                "config" -> body["config"]!!.jsonObject
                    .let { if (it.isEmpty()) config.remove(str("nest")) else config[str("nest")] = it }
                "order" -> order[str("nest")] = body["seq"]!!.jsonArray.map { it.jsonPrimitive.content }
                "cron" -> crons[str("origin")] = body["config"]!!.jsonObject
                "rest" -> if (!crons.containsKey(str("origin"))) return "crash: no cron" else crons.remove(str("origin"))
            }
            publish()
            return null
        }
    }

    private fun host(
        me: String = "~zod",
        hooksOnShip: Boolean = true,
        /** The ship's hooks before the screen reads them. */
        prepare: Hooks.() -> Unit = {},
        block: ComposeUiTest.(FakeShip, Hooks) -> Unit,
    ) {
        val tmp = createTempDirectory(prefix = "talon-disappear-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val ship = FakeShip("~zod").apply { scries["groups/v2/groups/$flag"] = record; ourChannels() }
        val hooks = Hooks(ship).also { it.prepare(); if (hooksOnShip) it.publish() }
        ship.refuse = { p -> if (p.app == "channels-server") hooks.take(p) else null }
        val repo = TlonChatRepo(db).apply { attachForTest(ship.channel, me) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ship.channel.events().launchIn(scope)
        try {
            runComposeUiTest {
                setContent {
                    TalonTheme(darkTheme = false) {
                        GroupAdminScreen(db = db, repo = repo, flag = flag, onBack = {}, me = me)
                    }
                }
                waitUntil(timeoutMillis = 5_000) { shows("The Garden") }
                block(ship, hooks)
            }
        } finally {
            scope.cancel()
            db.close()
            tmp.deleteRecursively()
        }
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.choose(label: String) {
        onNodeWithText(label).performScrollTo().performClick()
    }

    private fun hookPokes(ship: FakeShip) = ship.pokesTo("channels-server").map { it.json.toString() }

    private val week = Disappearing.delayNoun(Disappearing.WEEK)
    private val garden = Disappearing.groupNoun(flag)

    /** A channel's config, or its sweep's: the delay, for the group. */
    private fun cfg(delay: String, group: String = garden) = buildJsonObject { put("delay", delay); put("group", group) }
    private val weekHere = cfg(week)

    /** What our own %channels says of each channel we host here: its group. */
    private fun FakeShip.ourChannels(group: Map<String, String> = mapOf(general to flag, pics to flag)) {
        for ((n, g) in group) scries["channels/v5/$n/perm"] = """{"writers":[],"group":"$g"}"""
    }

    @Test
    fun `the host turns it on for every channel it hosts, says so, and turns it off`() = host { ship, hooks ->
        waitUntil(timeoutMillis = 5_000) { shows("Disappearing messages") && !shows("Reading…") }
        onNodeWithText("Off").assertIsSelected()
        assertTrue(shows("Not covered: Journal, Side."), "a notebook and a chat ~nec hosts")

        choose("1 week")
        waitUntil(timeoutMillis = 5_000) { shows("Delete messages in The Garden after 1 week?") }
        assertTrue(shows("Messages already older than that are deleted now."))
        onNodeWithText("Turn on").performClick()
        waitUntil(timeoutMillis = 10_000) { ship.pokesTo("channels").any { "Disappearing messages are on" in it.json.toString() } }

        val src = runBlocking { Disappearing.source() }
        assertEquals(
            listOf(
                buildJsonObject { put("add", buildJsonObject { put("name", Disappearing.HOOK_NAME); put("src", src) }) }.toString(),
                """{"config":{"id":"0v1.hook","nest":"$general","config":{"delay":"$week","group":"$garden"}}}""",
                """{"order":{"nest":"$general","seq":["0v1.hook"]}}""",
                """{"cron":{"id":"0v1.hook","origin":"$general","schedule":"~d1","config":{"delay":"$week","group":"$garden"}}}""",
                """{"config":{"id":"0v1.hook","nest":"$pics","config":{"delay":"$week","group":"$garden"}}}""",
                """{"order":{"nest":"$pics","seq":["0v1.hook"]}}""",
                """{"cron":{"id":"0v1.hook","origin":"$pics","schedule":"~d1","config":{"delay":"$week","group":"$garden"}}}""",
            ),
            hookPokes(ship),
        )
        assertTrue(ship.pokesTo("channels-server").all { it.mark == "hook-action-0" && it.ship.removePrefix("~") == "zod" })
        // The line in the description, the rest of it kept.
        assertTrue(ship.pokesTo("groups").any { "growing things\\n\\nMessages disappear after 1 week." in it.json.toString() }, ship.pokesTo("groups").toString())
        // The notice in the chat, not in the gallery or another ship's chat.
        val notices = ship.pokesTo("channels").filter { "Disappearing messages" in it.json.toString() }
        assertEquals(listOf(general), notices.map { it.json.jsonObject["channel"]!!.jsonObject["nest"]!!.jsonPrimitive.content })
        assertTrue("deleted for everyone 1 week after they are sent" in notices.single().json.toString())
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("1 week").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("1 week").assertIsSelected()

        // Off: no confirmation. What deletes goes first, the config and then
        // the sweep, and then each channel leaves the order.
        val before = hookPokes(ship).size
        choose("Off")
        waitUntil(timeoutMillis = 10_000) { ship.pokesTo("channels").any { "Disappearing messages are off." in it.json.toString() } }
        assertEquals(
            listOf(
                """{"config":{"id":"0v1.hook","nest":"$general","config":{}}}""",
                """{"rest":{"id":"0v1.hook","origin":"$general"}}""",
                """{"order":{"nest":"$general","seq":[]}}""",
                """{"config":{"id":"0v1.hook","nest":"$pics","config":{}}}""",
                """{"rest":{"id":"0v1.hook","origin":"$pics"}}""",
                """{"order":{"nest":"$pics","seq":[]}}""",
            ),
            hookPokes(ship).drop(before),
        )
        assertTrue(hooks.config.isEmpty() && hooks.crons.isEmpty())
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithText("Off").assertIsSelected() }.isSuccess }
    }

    @Test
    fun `nothing is deleted until the host confirms`() = host { ship, _ ->
        waitUntil(timeoutMillis = 5_000) { shows("1 day") }
        choose("1 day")
        waitUntil(timeoutMillis = 5_000) { shows("Delete messages in The Garden after 1 day?") }
        onNodeWithText("Cancel").performClick()
        waitForIdle()
        assertEquals(emptyList(), hookPokes(ship))
        onNodeWithText("Off").assertIsSelected()
    }

    @Test
    fun `a time of the host's own`() = host { ship, _ ->
        waitUntil(timeoutMillis = 5_000) { shows("Custom") }
        choose("Custom")
        onNode(hasSetTextAction() and hasText("How many")).performTextInput("3")
        onNodeWithText("days").performClick()
        onNodeWithText("Set").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("Delete messages in The Garden after 3 days?") }
        onNodeWithText("Turn on").performClick()
        waitUntil(timeoutMillis = 10_000) { hookPokes(ship).any { "cron" in it && pics in it } }
        assertTrue(hookPokes(ship).any { Disappearing.delayNoun(3 * Disappearing.DAY) in it })
        waitUntil(timeoutMillis = 5_000) { shows("Custom (3 days)") }
    }

    /** A Talon with a newer hook than the ship holds: it is edited in place, not added twice. */
    @Test
    fun `an older hook on the ship is brought up to date`() = host(prepare = { src = "an older hook" }) { ship, hooks ->
        waitUntil(timeoutMillis = 5_000) { shows("1 day") }
        choose("1 day")
        waitUntil(timeoutMillis = 5_000) { shows("Turn on") }
        onNodeWithText("Turn on").performClick()
        waitUntil(timeoutMillis = 10_000) { hookPokes(ship).any { "cron" in it && pics in it } }
        val src = runBlocking { Disappearing.source() }
        assertEquals(Disappearing.edit(hooks.id, src).toString(), hookPokes(ship).first())
        assertTrue(hookPokes(ship).none { it.startsWith("{\"add\"") })
        assertEquals(src, hooks.src)
    }

    @Test
    fun `channels set apart are said to differ`() = host(prepare = { on(general) }) { _, _ ->
        waitUntil(timeoutMillis = 5_000) { shows("The channels differ.") }
        assertTrue(!shows("only partly set up"))
    }

    /** [nest] fully on at a week: its config, its place in the order and its sweep. */
    private fun Hooks.on(nest: String) {
        src = "a hook"; config[nest] = cfg(week); order[nest] = listOf(id); crons[nest] = cfg(week)
    }

    /**
     * An off the ship half took: the config emptied, the sweep still
     * running, which deletes every day. It used to read as off, and Off
     * then did nothing, as it already was.
     */
    @Test
    fun `a switch-off the ship half took is not shown as off, and Off finishes it`() = host(prepare = { on(general); on(pics) }) { ship, hooks ->
        hooks.refuse = { if (it == "rest") "busy" else null }
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithText("1 week").assertIsSelected() }.isSuccess }
        choose("Off")
        waitUntil(timeoutMillis = 10_000) { shows("Couldn't change disappearing messages") }
        assertEquals(mapOf(general to weekHere, pics to weekHere), hooks.crons.toMap(), "both sweeps still run")
        waitUntil(timeoutMillis = 5_000) { shows("only partly set up here, so messages may still be deleted") }
        onNodeWithText("Off").assertIsNotSelected()
        assertTrue(ship.pokesTo("channels").none { "Disappearing messages" in it.json.toString() }, "not told it is off yet")

        hooks.refuse = { null }
        val before = hookPokes(ship).size
        choose("Off")
        waitUntil(timeoutMillis = 10_000) { ship.pokesTo("channels").any { "Disappearing messages are off." in it.json.toString() } }
        assertEquals(
            listOf(
                """{"rest":{"id":"0v1.hook","origin":"$general"}}""",
                """{"order":{"nest":"$general","seq":[]}}""",
                """{"config":{"id":"0v1.hook","nest":"$pics","config":{}}}""",
                """{"rest":{"id":"0v1.hook","origin":"$pics"}}""",
                """{"order":{"nest":"$pics","seq":[]}}""",
            ),
            hookPokes(ship).drop(before),
            "only what was left",
        )
        assertTrue(hooks.crons.isEmpty() && hooks.config.isEmpty())
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithText("Off").assertIsSelected() }.isSuccess }
        assertTrue(!shows("only partly set up"))
    }

    /** A Tlon channel template replaces the hook's config, but each sweep keeps its own. */
    @Test
    fun `a sweep left without its config is not shown as off`() = host(prepare = { on(general); config.clear() }) { _, _ ->
        waitUntil(timeoutMillis = 5_000) { shows("only partly set up here") }
        onNodeWithText("Off").assertIsNotSelected()
    }

    @Test
    fun `an admin who is not the host is not offered it, and the ship is not asked`() = host(me = "~nec") { ship, _ ->
        waitForIdle()
        assertTrue(!shows("Disappearing messages"))
        assertTrue(ship.scried.none { it.startsWith("channels-server") }, ship.scried.toString())
    }

    @Test
    fun `a ship whose Groups app has no hooks says so`() = host(hooksOnShip = false) { _, _ ->
        waitUntil(timeoutMillis = 5_000) { shows("too old for disappearing messages") }
    }

    @Test
    fun `a hook the ship cannot build changes no channel`() = host { ship, hooks ->
        hooks.compiles = false
        waitUntil(timeoutMillis = 5_000) { shows("8 hours") }
        choose("8 hours")
        waitUntil(timeoutMillis = 5_000) { shows("Turn on") }
        onNodeWithText("Turn on").performClick()
        waitUntil(timeoutMillis = 10_000) { shows("couldn't build the disappearing-messages hook") }
        assertEquals(listOf("add"), hookPokes(ship).map { it.substringAfter("{\"").substringBefore('"') })
        assertTrue(ship.pokesTo("groups").none { "Messages disappear" in it.json.toString() })
        assertTrue(ship.pokesTo("channels").isEmpty())
    }

    @Test
    fun `a refusal is said, and the screen shows what the ship holds`() = host { ship, hooks ->
        hooks.refuse = { if (it == "order") "crash" else null }
        waitUntil(timeoutMillis = 5_000) { shows("1 hour") }
        choose("1 hour")
        waitUntil(timeoutMillis = 5_000) { shows("Turn on") }
        onNodeWithText("Turn on").performClick()
        waitUntil(timeoutMillis = 10_000) { shows("Couldn't change disappearing messages") }
        // Configured but in no order and with no sweep: half on, said so, and nothing told.
        waitUntil(timeoutMillis = 5_000) { shows("only partly set up here") }
        onNodeWithText("Off").assertIsNotSelected()
        assertTrue(ship.pokesTo("channels").isEmpty() && ship.pokesTo("groups").isEmpty())
        // Off clears what the half-on left.
        hooks.refuse = { null }
        choose("Off")
        waitUntil(timeoutMillis = 10_000) { hooks.config.isEmpty() }
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithText("Off").assertIsSelected() }.isSuccess }
    }

    /** Real time, not the composition's clock: a slow ship, and a screen that goes. */
    @Test
    fun `it finishes when the screen goes`() = runBlocking {
        val tmp = createTempDirectory(prefix = "talon-disappear-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val ship = FakeShip("~zod").apply { scries["groups/v2/groups/$flag"] = record; ourChannels() }
        val hooks = Hooks(ship).also { it.publish() }
        ship.refuse = { p -> if (p.app == "channels-server") { Thread.sleep(150); hooks.take(p) } else null }
        val repo = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod") }
        val events = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ship.channel.events().launchIn(events)
        try {
            val group = repo.fetchGroupAdmin(flag)!!
            val screen = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            screen.launch { repo.setDisappearing(group, Disappearing.DAY) }
            kotlinx.coroutines.delay(200)
            screen.cancel()
            kotlinx.coroutines.withTimeout(15_000) {
                while (ship.pokesTo("channels").none { "Disappearing messages are on" in it.json.toString() }) kotlinx.coroutines.delay(20)
            }
            assertEquals(setOf(general, pics), hooks.crons.keys)
        } finally {
            events.cancel()
            db.close()
            tmp.deleteRecursively()
        }
    }

    /** A new channel made in the group, on a ship whose hooks [prepare] sets; what the ship then holds. */
    private fun newChannel(prepare: Hooks.() -> Unit, check: (Hooks, FakeShip, String) -> Unit) = runBlocking {
        val tmp = createTempDirectory(prefix = "talon-disappear-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val ship = FakeShip("~zod").apply { scries["groups/v2/groups/$flag"] = record; ourChannels() }
        val hooks = Hooks(ship)
        hooks.src = Disappearing.source()
        hooks.prepare()
        hooks.publish()
        ship.refuse = { p -> if (p.app == "channels-server") hooks.take(p) else null }
        val repo = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod") }
        val events = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ship.channel.events().launchIn(events)
        try {
            check(hooks, ship, repo.createChannel(flag, "chat", "Fresh"))
        } finally {
            events.cancel()
            db.close()
            tmp.deleteRecursively()
        }
    }

    /** Only from a group fully on: a half-on channel is no setting to copy. */
    @Test
    fun `a new channel copies nothing from a group partly on`() = newChannel(prepare = {
        val src = this.src
        on(general); this.src = src
        config[pics] = weekHere // half on: configured, not in the order, no sweep
    }) { _, ship, _ ->
        assertEquals(emptyList(), hookPokes(ship))
    }

    @Test
    fun `a new channel in a group with it on gets it too`() = runBlocking {
        val tmp = createTempDirectory(prefix = "talon-disappear-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val ship = FakeShip("~zod").apply { scries["groups/v2/groups/$flag"] = record; ourChannels() }
        val hooks = Hooks(ship)
        hooks.src = Disappearing.source()
        for (n in listOf(general, pics)) { hooks.config[n] = weekHere; hooks.order[n] = listOf(hooks.id); hooks.crons[n] = weekHere }
        hooks.publish()
        ship.refuse = { p -> if (p.app == "channels-server") hooks.take(p) else null }
        val repo = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod") }
        val events = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ship.channel.events().launchIn(events)
        try {
            val nest = repo.createChannel(flag, "chat", "Fresh")
            assertEquals(weekHere, hooks.config[nest])
            assertEquals(listOf(hooks.id), hooks.order[nest])
            assertTrue(nest in hooks.crons)
            assertTrue(hookPokes(ship).none { "\"add\"" in it || "\"edit\"" in it }, "the hook was already there")
        } finally {
            events.cancel()
            db.close()
            tmp.deleteRecursively()
        }
    }

    // ─── other groups ─────────────────────────────────────────

    /** This group's record, also listing a channel we host for another group, as any admin may add. */
    private val listsAnother = record.trimEnd().removeSuffix("}}") + """,
          "chat/~zod/orchard-chat":{"meta":{"title":"Orchard talk","description":"","image":"","cover":""},"added":5,"section":"default","readers":[],"join":true}}}"""

    @Test
    fun `a channel of another group listed here is never touched`() = host(prepare = {
        ship.scries["groups/v2/groups/$flag"] = listsAnother
        ship.ourChannels(mapOf(general to flag, pics to flag, "chat/~zod/orchard-chat" to "~zod/orchard"))
    }) { ship, hooks ->
        waitUntil(timeoutMillis = 5_000) { shows("Not covered:") }
        assertTrue(shows("Orchard talk"), "named as not covered")
        choose("1 week")
        waitUntil(timeoutMillis = 5_000) { shows("Turn on") }
        onNodeWithText("Turn on").performClick()
        waitUntil(timeoutMillis = 10_000) { ship.pokesTo("channels").any { "Disappearing messages are on" in it.json.toString() } }
        assertTrue(hookPokes(ship).none { "orchard" in it }, hookPokes(ship).toString())
        assertTrue(ship.pokesTo("channels").none { "orchard" in it.json.toString() }, "no notice there either")
        assertEquals(setOf(general, pics), hooks.config.keys)
    }

    @Test
    fun `a channel whose group can't be read stops the setting`() = host(prepare = { ship.failScry = { it == "channels/v5/$pics/perm" } }) { ship, _ ->
        waitUntil(timeoutMillis = 5_000) { shows("Couldn't read disappearing messages") }
        assertTrue(!shows("1 week"), "no choice is offered")
        assertEquals(emptyList(), hookPokes(ship))
    }

    /** A Talon from before the group was written in left no group: half on until set again. */
    @Test
    fun `a config with no group in it reads as partly set up`() = host(prepare = {
        for (n in listOf(general, pics)) {
            src = "a hook"; config[n] = buildJsonObject { put("delay", week) }; order[n] = listOf(id); crons[n] = buildJsonObject { put("delay", week) }
        }
    }) { _, _ ->
        waitUntil(timeoutMillis = 5_000) { shows("only partly set up here") }
        onNodeWithText("1 week").assertIsNotSelected()
    }

    /**
     * Going from one group's admin page to another's, the first group's
     * record is still on show for a moment. Its setting must not be.
     */
    @Test
    fun `the last group's setting is not offered on the next group's page`() {
        val tmp = createTempDirectory(prefix = "talon-disappear-").toFile()
        val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
        val orchard = "~zod/orchard"
        val ship = FakeShip("~zod").apply {
            scries["groups/v2/groups/$flag"] = record
            scries["groups/v2/groups/$orchard"] = record.replace("The Garden", "The Orchard")
            ourChannels()
            holdScry = { if (it == "groups/v2/groups/$orchard") 3_000 else 0 }
        }
        Hooks(ship).publish()
        val repo = TlonChatRepo(db).apply { attachForTest(ship.channel, "~zod") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ship.channel.events().launchIn(scope)
        try {
            runComposeUiTest {
                var shown by mutableStateOf(flag)
                setContent {
                    TalonTheme(darkTheme = false) { GroupAdminScreen(db = db, repo = repo, flag = shown, onBack = {}, me = "~zod") }
                }
                waitUntil(timeoutMillis = 5_000) { shows("Disappearing messages") && shows("1 week") }
                shown = orchard
                waitForIdle()
                assertTrue(shows("The Garden"), "still the last group's record")
                assertTrue(!shows("Disappearing messages"), "and none of its setting")
            }
        } finally {
            scope.cancel()
            db.close()
            tmp.deleteRecursively()
        }
    }

    /**
     * The hook as shipped is the one run on a fake ship (~bel, tlon-apps
     * groups 938f0c4261): history swept a hundred at a time, a new post
     * deleted on its own timer, nothing deleted once off, and nothing in
     * a channel of another group given this group's config. Change the file
     * and run scripts/disappearing-hook-check.py on a fake ship again
     * before changing this hash.
     */
    @Test
    fun `the hook is the one run on a ship`() {
        val bytes = runBlocking { Disappearing.source() }.encodeToByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("ec815b833681875f43c64f6ab3da6522bc1d786ad3d2ff535aa6e8b5ea70abf7", hash)
    }
}
