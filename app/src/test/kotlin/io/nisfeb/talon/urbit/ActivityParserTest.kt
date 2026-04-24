package io.nisfeb.talon.urbit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityParserTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ─── sourceKeyToWhom ────────────────────────────────────────

    @Test
    fun `ship source key strips ship prefix`() {
        assertEquals("~sampel-palnet", sourceKeyToWhom("ship/~sampel-palnet"))
    }

    @Test
    fun `club source key strips club prefix`() {
        assertEquals("0v2.abcde", sourceKeyToWhom("club/0v2.abcde"))
    }

    @Test
    fun `channel source key strips channel prefix`() {
        assertEquals(
            "chat/~sampel-palnet/general",
            sourceKeyToWhom("channel/chat/~sampel-palnet/general"),
        )
    }

    @Test
    fun `group thread and base source keys yield null`() {
        // We don't surface these kinds in the home list.
        assertNull(sourceKeyToWhom("group/~sampel/my-group"))
        assertNull(sourceKeyToWhom("thread/chat/~x/y"))
        assertNull(sourceKeyToWhom("base"))
    }

    // ─── sourceToWhom ───────────────────────────────────────────

    @Test
    fun `dm ship source resolves to the peer`() {
        val src = buildJsonObject {
            put("dm", buildJsonObject { put("ship", "~sampel") })
        }
        assertEquals("~sampel", sourceToWhom(src))
    }

    @Test
    fun `dm club source resolves to the club id`() {
        val src = buildJsonObject {
            put("dm", buildJsonObject { put("club", "0v2.abcde") })
        }
        assertEquals("0v2.abcde", sourceToWhom(src))
    }

    @Test
    fun `channel source resolves to nest`() {
        val src = buildJsonObject {
            put("channel", buildJsonObject {
                put("nest", "chat/~host/slug")
                put("group", "~host/flag")
            })
        }
        assertEquals("chat/~host/slug", sourceToWhom(src))
    }

    @Test
    fun `unknown source object yields null`() {
        val src = buildJsonObject { put("mystery", JsonNull) }
        assertNull(sourceToWhom(src))
    }

    // ─── toUnread ───────────────────────────────────────────────

    @Test
    fun `toUnread populates count notify-count and recency`() {
        val summary = json.parseToJsonElement("""
            {"count":7,"notify-count":3,"recency":1777000000000,"notify":true}
        """.trimIndent()).jsonObject
        val row = toUnread("ship/~sampel", summary)!!
        assertEquals("~sampel", row.whom)
        assertEquals(7, row.count)
        assertEquals(3, row.notifyCount)
        assertEquals(1777000000000L, row.recencyMs)
    }

    @Test
    fun `toUnread missing fields default to 0`() {
        val row = toUnread("ship/~sampel", buildJsonObject { })!!
        assertEquals(0, row.count)
        assertEquals(0, row.notifyCount)
        assertEquals(0L, row.recencyMs)
    }

    @Test
    fun `toUnread with unsurfaced source kind yields null`() {
        assertNull(toUnread("group/~sampel/flag", buildJsonObject { }))
        assertNull(toUnread("thread/x/y", buildJsonObject { }))
        assertNull(toUnread("base", buildJsonObject { }))
    }

    @Test
    fun `toUnread override whom wins over sourceKey`() {
        val row = toUnread(
            sourceKey = null,
            summary = buildJsonObject { put("count", 1) },
            overrideWhom = "~sampel",
        )!!
        assertEquals("~sampel", row.whom)
    }

    // ─── end-to-end fixture ─────────────────────────────────────

    @Test
    fun `real activity update fixture parses every surfaced source`() {
        val payload = Fixtures.loadObject("activity/update-channel-unread.json")
        val map = payload["activity"]!!.jsonObject
        val rows = map.entries.mapNotNull { (key, summary) ->
            toUnread(key, summary as? JsonObject ?: return@mapNotNull null)
        }
        val byWhom = rows.associateBy { it.whom }
        // Five source entries in the fixture; we surface three
        // (channel / ship / club). group/base are dropped.
        assertEquals(3, byWhom.size)

        val channelRow = byWhom["chat/~ricsul-bilwyt-dozzod-nisfeb/v2c3drm0"]!!
        assertEquals(3, channelRow.count)
        assertEquals(1, channelRow.notifyCount)
        assertEquals(1777054928725L, channelRow.recencyMs)

        val clubRow = byWhom["0v2.abcde.fghij"]!!
        assertEquals(5, clubRow.count)
        assertEquals(2, clubRow.notifyCount)

        val shipRow = byWhom["~sampel-palnet"]!!
        assertEquals(0, shipRow.notifyCount)
    }

    // ─── activityReadSource + activityReadAction ───────────────

    @Test
    fun `read source for a DM wraps ship`() {
        val src = activityReadSource("~sampel")!!
        val dm = src["dm"]!!.jsonObject
        assertEquals("~sampel", dm["ship"]!!.jsonPrimitive.content)
    }

    @Test
    fun `read source for a club wraps club id`() {
        val src = activityReadSource("0v2.abcde")!!
        val dm = src["dm"]!!.jsonObject
        assertEquals("0v2.abcde", dm["club"]!!.jsonPrimitive.content)
    }

    @Test
    fun `read source for a channel carries nest and group`() {
        val src = activityReadSource("chat/~host/slug", groupFlag = "~host/flag")!!
        val ch = src["channel"]!!.jsonObject
        assertEquals("chat/~host/slug", ch["nest"]!!.jsonPrimitive.content)
        assertEquals("~host/flag", ch["group"]!!.jsonPrimitive.content)
    }

    @Test
    fun `read source for a channel without group flag yields null`() {
        // Defensive — caller must resolve group first.
        assertNull(activityReadSource("chat/~host/slug", groupFlag = null))
    }

    @Test
    fun `read source covers diary and heap channels too`() {
        assertTrue(activityReadSource("diary/~h/s", "~h/f") != null)
        assertTrue(activityReadSource("heap/~h/s", "~h/f") != null)
    }

    @Test
    fun `activityReadAction wraps source under read all time null deep false`() {
        val src = activityReadSource("~sampel")!!
        val body = activityReadAction(src)
        val read = body["read"]!!.jsonObject
        assertTrue(read["source"] is JsonObject)
        val all = read["action"]!!.jsonObject["all"]!!.jsonObject
        assertEquals(JsonNull, all["time"])
        assertEquals(false, all["deep"]!!.jsonPrimitive.content.toBoolean())
    }
}
