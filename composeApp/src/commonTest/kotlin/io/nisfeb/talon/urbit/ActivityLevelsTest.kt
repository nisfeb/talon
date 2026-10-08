package io.nisfeb.talon.urbit

import io.nisfeb.talon.data.NotifyLevel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Talon's levels as Tlon's client writes them. The maps below are what
 * Tlon's own getVolumeMap (packages/api/src/urbit/activity.ts, develop,
 * 2026-10-08) returns, run as it is; a ship reads the same level back
 * whichever client set it.
 */
class ActivityLevelsTest {
    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject

    private val SOFT = """{"post":{"unreads":true,"notify":false},"post-mention":{"unreads":true,"notify":true},"reply":{"unreads":true,"notify":false},"reply-mention":{"unreads":true,"notify":true},"react":{"unreads":false,"notify":false},"dm-react":{"unreads":false,"notify":false},"dm-invite":{"unreads":true,"notify":true},"dm-post":{"unreads":true,"notify":true},"dm-post-mention":{"unreads":true,"notify":true},"dm-reply":{"unreads":true,"notify":true},"dm-reply-mention":{"unreads":true,"notify":true},"group-ask":{"unreads":true,"notify":true},"group-join":{"unreads":true,"notify":false},"group-kick":{"unreads":true,"notify":false},"group-invite":{"unreads":true,"notify":true},"group-role":{"unreads":true,"notify":false},"flag-post":{"unreads":true,"notify":true},"flag-reply":{"unreads":true,"notify":true},"note-create":{"unreads":true,"notify":false},"note-edit":{"unreads":true,"notify":false}}"""
    private val MEDIUM = """{"post":{"unreads":true,"notify":true},"post-mention":{"unreads":true,"notify":true},"reply":{"unreads":true,"notify":false},"reply-mention":{"unreads":true,"notify":true},"react":{"unreads":false,"notify":true},"dm-react":{"unreads":false,"notify":true},"dm-invite":{"unreads":true,"notify":true},"dm-post":{"unreads":true,"notify":true},"dm-post-mention":{"unreads":true,"notify":true},"dm-reply":{"unreads":true,"notify":true},"dm-reply-mention":{"unreads":true,"notify":true},"group-ask":{"unreads":true,"notify":true},"group-join":{"unreads":true,"notify":false},"group-kick":{"unreads":true,"notify":false},"group-invite":{"unreads":true,"notify":true},"group-role":{"unreads":true,"notify":false},"flag-post":{"unreads":true,"notify":true},"flag-reply":{"unreads":true,"notify":true},"note-create":{"unreads":true,"notify":true},"note-edit":{"unreads":true,"notify":true}}"""
    private val HUSH = """{"post":{"unreads":true,"notify":false},"post-mention":{"unreads":true,"notify":false},"reply":{"unreads":true,"notify":false},"reply-mention":{"unreads":true,"notify":false},"react":{"unreads":false,"notify":false},"dm-react":{"unreads":false,"notify":false},"dm-invite":{"unreads":true,"notify":false},"dm-post":{"unreads":true,"notify":false},"dm-post-mention":{"unreads":true,"notify":false},"dm-reply":{"unreads":true,"notify":false},"dm-reply-mention":{"unreads":true,"notify":false},"group-ask":{"unreads":true,"notify":false},"group-join":{"unreads":true,"notify":false},"group-kick":{"unreads":true,"notify":false},"group-invite":{"unreads":true,"notify":false},"group-role":{"unreads":true,"notify":false},"flag-post":{"unreads":true,"notify":false},"flag-reply":{"unreads":true,"notify":false},"note-create":{"unreads":true,"notify":false},"note-edit":{"unreads":true,"notify":false}}"""
    private val LOUD = """{"post":{"unreads":true,"notify":true},"post-mention":{"unreads":true,"notify":true},"reply":{"unreads":true,"notify":true},"reply-mention":{"unreads":true,"notify":true},"react":{"unreads":false,"notify":true},"dm-react":{"unreads":false,"notify":true},"dm-invite":{"unreads":true,"notify":true},"dm-post":{"unreads":true,"notify":true},"dm-post-mention":{"unreads":true,"notify":true},"dm-reply":{"unreads":true,"notify":true},"dm-reply-mention":{"unreads":true,"notify":true},"group-ask":{"unreads":true,"notify":true},"group-join":{"unreads":true,"notify":true},"group-kick":{"unreads":true,"notify":true},"group-invite":{"unreads":true,"notify":true},"group-role":{"unreads":true,"notify":true},"flag-post":{"unreads":true,"notify":true},"flag-reply":{"unreads":true,"notify":true},"note-create":{"unreads":true,"notify":true},"note-edit":{"unreads":true,"notify":true}}"""

    @Test
    fun `each level's map is Tlon's own`() {
        assertEquals(json(SOFT), ActivityLevels.volumeMap("soft"))
        assertEquals(json(MEDIUM), ActivityLevels.volumeMap("medium"))
        assertEquals(json(HUSH), ActivityLevels.volumeMap("hush"))
        assertEquals(json(LOUD), ActivityLevels.volumeMap("loud"))
    }

    @Test
    fun `Talon's levels are Tlon's soft, medium and hush`() {
        assertEquals("soft", ActivityLevels.tlonLevel(NotifyLevel.MENTIONS))
        assertEquals("medium", ActivityLevels.tlonLevel(NotifyLevel.ALL))
        assertEquals("hush", ActivityLevels.tlonLevel(NotifyLevel.NONE))
        // the point of it: mentions only is a plain post that does not notify
        val soft = ActivityLevels.volumeMap(ActivityLevels.tlonLevel(NotifyLevel.MENTIONS))
        assertEquals(json("""{"unreads":true,"notify":false}"""), soft["post"])
        assertEquals(json("""{"unreads":true,"notify":true}"""), soft["post-mention"])
    }

    // Tlon's default-volumes (sur/activity.hoon), as the ship answers it
    private val STOCK = """{"post":{"unreads":true,"notify":true},"reply":{"unreads":true,"notify":false},"react":{"unreads":false,"notify":true},
        "post-mention":{"unreads":true,"notify":true},"reply-mention":{"unreads":true,"notify":true},"dm-invite":{"unreads":true,"notify":true},
        "dm-post":{"unreads":true,"notify":true},"dm-post-mention":{"unreads":true,"notify":true},"dm-reply":{"unreads":true,"notify":true},
        "dm-reply-mention":{"unreads":true,"notify":true},"dm-react":{"unreads":false,"notify":true},"group-invite":{"unreads":true,"notify":true},
        "group-ask":{"unreads":true,"notify":true},"flag-post":{"unreads":true,"notify":true},"flag-reply":{"unreads":true,"notify":true},
        "group-kick":{"unreads":true,"notify":false},"group-join":{"unreads":true,"notify":false},"group-role":{"unreads":true,"notify":false},
        "contact":{"unreads":false,"notify":false},"note-create":{"unreads":true,"notify":true},"note-edit":{"unreads":true,"notify":true}}"""

    @Test
    fun `a base nobody set is the stock one, and only that`() {
        assertTrue(ActivityLevels.isStock(null), "absent: never set")
        assertTrue(ActivityLevels.isStock(json(STOCK)))
        assertTrue(ActivityLevels.isStock(JsonObject(json(STOCK).filterKeys { !it.startsWith("note-") })), "an older ship's map, without the newer events")
        assertFalse(ActivityLevels.isStock(json(SOFT)), "soft: someone chose it")
        assertFalse(ActivityLevels.isStock(json(LOUD)), "loud differs on reply")
        assertFalse(ActivityLevels.isStock(JsonObject(emptyMap())))
        assertFalse(ActivityLevels.isStock(json("""{"post":{"unreads":true,"notify":true},"mystery":{"unreads":true,"notify":true}}""")))
    }

    @Test
    fun `the source a level is about, its key, and the adjust`() {
        assertEquals(json("""{"channel":{"nest":"chat/~bus/general","group":"~bus/club"}}"""), ActivityLevels.source("chat/~bus/general", "~bus/club"))
        assertEquals(json("""{"group":"~bus/club"}"""), ActivityLevels.source("group/~bus/club", null))
        assertNull(ActivityLevels.source("chat/~bus/general", null), "a channel whose group is not known yet")
        assertNull(ActivityLevels.source("~sampel-palnet", null), "a DM keeps Tlon's levels")
        assertNull(ActivityLevels.source("0v4.abcde", null))
        assertEquals("channel/heap/~bus/pics", ActivityLevels.settingsKey("heap/~bus/pics"))
        assertEquals("group/~bus/club", ActivityLevels.settingsKey("group/~bus/club"))
        assertNull(ActivityLevels.settingsKey("~sampel-palnet"))
        assertEquals(
            json("""{"adjust":{"source":{"group":"~bus/club"},"volume":null}}"""),
            ActivityLevels.adjust(json("""{"group":"~bus/club"}"""), null),
            "back to the group's: the source's own map dropped",
        )
        assertEquals(json("""{"adjust":{"source":{"base":null},"volume":$SOFT}}"""), ActivityLevels.adjust(ActivityLevels.BASE, ActivityLevels.volumeMap("soft")))
    }
}
