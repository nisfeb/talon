package io.nisfeb.talon.urbit

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.ChannelGroupEntity
import io.nisfeb.talon.data.NotifyLevel
import io.nisfeb.talon.data.NotifyPreferenceEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Talon's levels told to %activity, through the app's own settings path
 * against a stand-in ship. A user's report, 2026-10-08: on a ship at
 * Tlon's stock base every channel post notified, so every room wore the
 * Mentions marker while its Talon level said Mentions only.
 */
class ActivityLevelsMirrorTest {
    private val dir = createTempDirectory(prefix = "talon-levels-").toFile()
    private val db: AppDatabase = Room.databaseBuilder<AppDatabase>(File(dir, "t.db").absolutePath)
        .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
    private val ship = FakeShip("~zod")
    private val sync = SettingsSyncImpl(db = db, aiSettings = FakeAiSettings()).apply { attach(ship.channel) }
    private val repo = TlonChatRepo(db, settingsSync = sync).apply { attachForTest(ship.channel, "~zod") }
    private val nest = "chat/~bus/general"
    private val other = "heap/~bus/pics"

    @AfterTest
    fun close() {
        runBlocking { repo.stopAndJoinForTest() }
        db.close()
        dir.deleteRecursively()
    }

    private fun live(body: suspend CoroutineScope.() -> Unit) = runBlocking<Unit> {
        val events = ship.channel.events().launchIn(this)
        db.groups().upsertChannelGroups(listOf(ChannelGroupEntity(nest, "~bus/club"), ChannelGroupEntity(other, "~bus/club")))
        try { body() } finally { events.cancel() }
    }

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun map(level: String) = ActivityLevels.volumeMap(ActivityLevels.tlonLevel(level))
    private fun adjust(source: String, volume: JsonObject?) = ActivityLevels.adjust(json(source), volume)
    private fun activity() = ship.pokesTo("activity")

    @Test
    fun `a room's level is told to activity as Tlon's map, on the channel's source`() = live {
        sync.setNotifyLevel(nest, NotifyLevel.MENTIONS)
        val p = activity().single()
        assertEquals("activity-action-2", p.mark)
        assertEquals(adjust("""{"channel":{"nest":"$nest","group":"~bus/club"}}""", map(NotifyLevel.MENTIONS)), p.json)
        assertEquals(json("""{"unreads":true,"notify":false}"""), ((p.json as JsonObject)["adjust"] as JsonObject)["volume"]!!.jsonObject["post"],
            "a plain post no longer notifies, so it is no longer a mention")
        assertTrue(ship.pokesTo("settings").isNotEmpty(), "and the level is kept in Talon's settings as before")
    }

    @Test
    fun `a group's level goes to the group, all is Tlon's medium and mute its hush`() = live {
        sync.setNotifyLevel("group/~bus/club", NotifyLevel.ALL)
        sync.setNotifyLevel(nest, NotifyLevel.NONE)
        val (g, c) = activity()
        assertEquals(adjust("""{"group":"~bus/club"}""", map(NotifyLevel.ALL)), g.json)
        assertEquals(adjust("""{"channel":{"nest":"$nest","group":"~bus/club"}}""", ActivityLevels.volumeMap("hush")), c.json)
    }

    @Test
    fun `same as the group drops the room's own map, so it follows the group's`() = live {
        sync.clearNotifyLevel(nest)
        assertEquals(adjust("""{"channel":{"nest":"$nest","group":"~bus/club"}}""", null), activity().single().json)
    }

    @Test
    fun `a DM keeps Tlon's levels, nothing told to activity`() = live {
        sync.setNotifyLevel("~sampel-palnet", NotifyLevel.MENTIONS)
        assertTrue(activity().isEmpty(), "${activity()}")
    }

    // Tlon's default-volumes, as a ship nobody adjusted answers its base
    private val stock = """{"post":{"unreads":true,"notify":true},"reply":{"unreads":true,"notify":false},"react":{"unreads":false,"notify":true},
        "post-mention":{"unreads":true,"notify":true},"reply-mention":{"unreads":true,"notify":true},"dm-post":{"unreads":true,"notify":true}}"""

    @Test
    fun `at connect a stock base becomes mentions only, and levels the ship never heard are told`() = live {
        db.notifyPrefs().upsert(NotifyPreferenceEntity(nest, NotifyLevel.ALL))
        db.notifyPrefs().upsert(NotifyPreferenceEntity(other, NotifyLevel.MENTIONS))
        // the ship has its own map for the second channel, which Tlon's client may have written
        ship.scries["activity/v6/volume-settings"] = """{"base":$stock,"channel/$other":${ActivityLevels.volumeMap("loud")}}"""
        repo.bootstrapFollowedThreadsForTest()
        val sent = activity().map { it.json }
        assertEquals(
            listOf(
                adjust("""{"base":null}""", map(NotifyLevel.MENTIONS)),
                adjust("""{"channel":{"nest":"$nest","group":"~bus/club"}}""", map(NotifyLevel.ALL)),
            ),
            sent,
            "the base, and the level the ship had no word of; the other channel's own map is left as it is",
        )
    }

    @Test
    fun `a base someone set is left alone`() = live {
        ship.scries["activity/v6/volume-settings"] = """{"base":${ActivityLevels.volumeMap("loud")}}"""
        repo.bootstrapFollowedThreadsForTest()
        assertTrue(activity().isEmpty(), "${activity()}")
    }

    @Test
    fun `a refusal never undoes the level, and the level is told again at the next connect`() = live {
        ship.refuse = { if (it.app == "activity") "no such source" else null }
        sync.setNotifyLevel(nest, NotifyLevel.NONE)
        assertEquals(NotifyLevel.NONE, db.notifyPrefs().levelFor(nest), "kept, and Talon's pushes follow it")
        ship.refuse = { null }
        ship.pokes.clear()
        // the ship still has the old map for the channel: the owed level goes regardless
        ship.scries["activity/v6/volume-settings"] = """{"base":${ActivityLevels.volumeMap("soft")},"channel/$nest":${ActivityLevels.volumeMap("soft")}}"""
        repo.bootstrapFollowedThreadsForTest()
        assertEquals(listOf(adjust("""{"channel":{"nest":"$nest","group":"~bus/club"}}""", ActivityLevels.volumeMap("hush"))), activity().map { it.json })
        ship.pokes.clear()
        repo.bootstrapFollowedThreadsForTest()
        assertTrue(activity().isEmpty(), "told once: not again")
    }

    @Test
    fun `a refused base write does not stop the levels`() = live {
        db.notifyPrefs().upsert(NotifyPreferenceEntity(nest, NotifyLevel.ALL))
        ship.refuse = { if (it.app == "activity" && "\"base\"" in it.json.toString()) "no" else null }
        ship.scries["activity/v6/volume-settings"] = """{"base":$stock}"""
        repo.bootstrapFollowedThreadsForTest()
        assertEquals(adjust("""{"channel":{"nest":"$nest","group":"~bus/club"}}""", map(NotifyLevel.ALL)), activity().last().json)
    }

    @Test
    fun `a back-to-the-group that did not go is sent at the next connect`() = live {
        ship.refuse = { if (it.app == "activity") "no" else null }
        sync.clearNotifyLevel(nest)
        ship.refuse = { null }
        ship.pokes.clear()
        // the ship still holds the channel's old map
        ship.scries["activity/v6/volume-settings"] = """{"base":${ActivityLevels.volumeMap("soft")},"channel/$nest":${ActivityLevels.volumeMap("loud")}}"""
        repo.bootstrapFollowedThreadsForTest()
        assertEquals(listOf(adjust("""{"channel":{"nest":"$nest","group":"~bus/club"}}""", null)), activity().map { it.json })
        ship.pokes.clear()
        repo.bootstrapFollowedThreadsForTest()
        assertTrue(activity().isEmpty(), "told once: not again")
    }

    @Test
    fun `a level set while not connected goes at connect`() = live {
        // a repo with no channel yet takes over the settings hook
        val offline = TlonChatRepo(db, settingsSync = sync)
        try {
            sync.setNotifyLevel(nest, NotifyLevel.NONE)
            assertTrue(activity().isEmpty(), "${activity()}")
            offline.attachForTest(ship.channel, "~zod")
            // the ship has an entry, but this level never reached it
            ship.scries["activity/v6/volume-settings"] = """{"base":${ActivityLevels.volumeMap("soft")},"channel/$nest":${ActivityLevels.volumeMap("soft")}}"""
            offline.bootstrapFollowedThreadsForTest()
            assertEquals(listOf(adjust("""{"channel":{"nest":"$nest","group":"~bus/club"}}""", ActivityLevels.volumeMap("hush"))), activity().map { it.json })
        } finally {
            offline.stopAndJoinForTest()
        }
    }
}
