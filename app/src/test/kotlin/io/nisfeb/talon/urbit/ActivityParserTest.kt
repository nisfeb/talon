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
    fun `group and base source keys yield null`() {
        // We don't surface these kinds in the home list. (Thread
        // variants used to fall here too — see the dedicated section
        // below for the deep-link routing of `thread/` and
        // `dm-thread/`.)
        assertNull(sourceKeyToWhom("group/~sampel/my-group"))
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

    // ─── parseActivityEventTarget — deep-link extraction ────────

    private fun obj(raw: String) = json.parseToJsonElement(raw).jsonObject

    @Test
    fun `post-mention extracts the post id and no parent`() {
        // Top-level mention — tap should scroll to this post in the
        // chat, no thread routing.
        val ev = obj("""{"key":{"id":"~author/170.141.184.507.933.044.937.549.665.940.933.705.728"}}""")
        val t = parseActivityEventTarget("post-mention", ev)
        assertEquals("~author/170141184507933044937549665940933705728", t.postId)
        assertEquals(null, t.parentPostId)
    }

    @Test
    fun `reply event with parent extracts both ids`() {
        // Reply that mentions you — tap should open the thread for
        // parent and anchor on this reply.
        val ev = obj(
            """
            {"key":{"id":"~r/9.999"},
             "parent":{"id":"~p/1.000"}}
            """.trimIndent()
        )
        val t = parseActivityEventTarget("reply", ev)
        assertEquals("~r/9999", t.postId)
        assertEquals("~p/1000", t.parentPostId)
    }

    @Test
    fun `dm-reply-mention also handled`() {
        // Same shape but the wire tag has the dm- prefix; the parser
        // gates on `tag.contains("reply")` so both tag families work.
        val ev = obj(
            """
            {"key":{"id":"~r/2"},
             "parent":{"id":"~p/1"}}
            """.trimIndent()
        )
        val t = parseActivityEventTarget("dm-reply-mention", ev)
        assertEquals("~r/2", t.postId)
        assertEquals("~p/1", t.parentPostId)
    }

    @Test
    fun `reply-mention falls back to top when parent is missing`() {
        // Some shapes carry the parent under `top` instead of `parent`.
        val ev = obj(
            """
            {"key":{"id":"~r/2.000"},
             "top":{"id":"~p/1.000"}}
            """.trimIndent()
        )
        val t = parseActivityEventTarget("reply-mention", ev)
        assertEquals("~r/2000", t.postId)
        assertEquals("~p/1000", t.parentPostId)
    }

    @Test
    fun `reply tag with no parent treats keyId as the parent`() {
        // Defensive: a reply event missing `parent`/`top` is malformed
        // but observed on certain ships. Best-effort routes the user
        // into the conversation; the keyId we have is the only id and
        // it's the parent in the reply context.
        val ev = obj("""{"key":{"id":"~p/1"}}""")
        val t = parseActivityEventTarget("reply", ev)
        assertEquals(null, t.postId)
        assertEquals("~p/1", t.parentPostId)
    }

    @Test
    fun `event with no key falls back to top-level id field`() {
        // Older / shorter event shapes carry `id` directly without
        // wrapping in `key`. Keeps deep-linking working on legacy
        // ships that haven't moved to the wrapped form.
        val ev = obj("""{"id":"~author/1.234"}""")
        val t = parseActivityEventTarget("post-mention", ev)
        assertEquals("~author/1234", t.postId)
        assertEquals(null, t.parentPostId)
    }

    @Test
    fun `unknown tag treats event as top-level`() {
        val ev = obj("""{"key":{"id":"~author/1"}}""")
        val t = parseActivityEventTarget("group-invite", ev)
        assertEquals("~author/1", t.postId)
        assertEquals(null, t.parentPostId)
    }

    @Test
    fun `empty event returns nulls`() {
        val t = parseActivityEventTarget("post-mention", obj("{}"))
        assertEquals(null, t.postId)
        assertEquals(null, t.parentPostId)
    }

    // ─── sourceKeyToWhom — thread variants ──────────────────────

    @Test
    fun `thread source-key resolves to underlying channel nest`() {
        // `thread/<nest>/<msg-id>` — strip the trailing msg-id back
        // off to get the channel's nest.
        assertEquals(
            "chat/~host/slug",
            sourceKeyToWhom("thread/chat/~host/slug/170.141.184.507"),
        )
    }

    @Test
    fun `thread source-key with author-prefixed msg-id still resolves`() {
        // Some shapes carry the msg-id as `~author/<da>` (two extra
        // path segments). The nest is always exactly 3 segments, so
        // we always slice the first 3.
        assertEquals(
            "chat/~host/slug",
            sourceKeyToWhom("thread/chat/~host/slug/~author/170.141.184.507"),
        )
    }

    @Test
    fun `dm-thread source-key resolves to ship`() {
        assertEquals(
            "~sampel-palnet",
            sourceKeyToWhom("dm-thread/~sampel-palnet/~author/170.141"),
        )
    }

    @Test
    fun `dm-thread source-key resolves to club`() {
        assertEquals(
            "0v4.abcde",
            sourceKeyToWhom("dm-thread/0v4.abcde/~author/170.141"),
        )
    }

    @Test
    fun `unknown source-key kind still returns null`() {
        assertEquals(null, sourceKeyToWhom("base"))
        assertEquals(null, sourceKeyToWhom("group/~host/group-name"))
        assertEquals(null, sourceKeyToWhom("contact/~sampel"))
    }

    // ─── canonicalPostIdForWhom — DB-form normalization ─────────

    @Test
    fun `channel whom strips author-prefixed id back to bare da`() {
        // Activity events always emit `~author/<da>` (the message-key
        // shape) but channel tables in our DB key on bare `<da>`.
        // Deep-link lookups would miss without this stripping.
        assertEquals(
            "170141184507932790143209384169177088000",
            canonicalPostIdForWhom(
                "chat/~minder-folden/v3imqe1v",
                "~ricsul-bilwyt-dozzod-nisfeb/170141184507932790143209384169177088000",
            ),
        )
    }

    @Test
    fun `channel whom is a no-op on already-bare id`() {
        // A wire id that already came without the author prefix
        // shouldn't get further mangled.
        assertEquals(
            "170141184507932790143209384169177088000",
            canonicalPostIdForWhom(
                "chat/~h/s",
                "170141184507932790143209384169177088000",
            ),
        )
    }

    @Test
    fun `dm whom keeps the author-prefixed id intact`() {
        // DM writs are stored as `~author/<da>`. Stripping would
        // break lookups in the other direction.
        assertEquals(
            "~ricsul-bilwyt-dozzod-nisfeb/170141",
            canonicalPostIdForWhom(
                "~sampel-palnet",
                "~ricsul-bilwyt-dozzod-nisfeb/170141",
            ),
        )
    }

    @Test
    fun `club whom keeps the author-prefixed id intact`() {
        assertEquals(
            "~author/170141",
            canonicalPostIdForWhom("0v4.abcde", "~author/170141"),
        )
    }

    @Test
    fun `diary and heap channels normalize like chat`() {
        assertEquals("170141", canonicalPostIdForWhom("diary/~h/s", "~a/170141"))
        assertEquals("170141", canonicalPostIdForWhom("heap/~h/s", "~a/170141"))
    }

    @Test
    fun `null inputs short-circuit to null`() {
        assertEquals(null, canonicalPostIdForWhom("chat/~h/s", null))
        assertEquals(null, canonicalPostIdForWhom(null, null))
    }

    @Test
    fun `null whom passes the id through unchanged`() {
        // Defensive — if we ever route via something that doesn't
        // know its whom, leave the wire form alone rather than
        // silently mangling.
        assertEquals(
            "~author/170141",
            canonicalPostIdForWhom(null, "~author/170141"),
        )
    }
}
