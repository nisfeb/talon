package io.nisfeb.talon.urbit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class ActivityParserTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ─── sourceKeyToWhom ────────────────────────────────────────

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
            {"count":9,"notify-count":3,"recency":1777000000000,"notify":true,
             "unread":{"id":"~sampel/170.141","time":"170.141","count":7,"notify":true}}
        """.trimIndent()).jsonObject
        val row = toUnread("ship/~sampel", summary)!!
        assertEquals("~sampel", row.whom)
        // 9 on the wire includes two thread replies; the badge is the
        // main stream's own 7.
        assertEquals(7, row.count)
        assertEquals(3, row.notifyCount)
        assertEquals(1777000000000L, row.recencyMs)
    }

    // A channel whose own messages are all read, with four notifying
    // replies in its threads: no mentions on the channel's row. They
    // were, for good, since reading the channel leaves its threads.
    @Test
    fun `toUnread counts no mentions that live only in threads`() {
        val caughtUp = json.parseToJsonElement("""{"count":4,"notify-count":4,"notify":true,"unread":null}""").jsonObject
        assertEquals(0, toUnread("channel/chat/~host/b", caughtUp)!!.notifyCount)
        val quiet = json.parseToJsonElement(
            """{"count":6,"notify-count":4,"notify":true,"unread":{"id":"~sampel/170.141","count":2,"notify":false}}""",
        ).jsonObject
        assertEquals(0, toUnread("channel/chat/~host/b", quiet)!!.notifyCount, "its own two do not notify")
    }

    @Test
    fun `toUnread parses channel unread boundary to bare undotted da`() {
        // channel/<nest> source: MessageEntity.id is the bare undotted
        // @da, so the wire `~author/<dotted-da>` must reduce to it.
        val summary = json.parseToJsonElement("""
            {"count":2,"unread":{"id":"~zod/170.141.184.507.988.700.723.732","time":"x","count":2,"notify":false}}
        """.trimIndent()).jsonObject
        val row = toUnread("channel/chat/~host/general", summary)!!
        assertEquals("170141184507988700723732", row.firstUnreadId)
    }

    @Test
    fun `toUnread keeps author prefix for DM unread boundary`() {
        // ship/club source: MessageEntity.id keeps the ~author prefix,
        // only the @da is undotted.
        val summary = json.parseToJsonElement("""
            {"count":1,"unread":{"id":"~bus/170.141.184.507.111.222.333","time":"x","count":1,"notify":true}}
        """.trimIndent()).jsonObject
        val row = toUnread("ship/~bus", summary)!!
        assertEquals("~bus/170141184507111222333", row.firstUnreadId)
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
    fun `read source covers diary and heap channels too`() {
        assertTrue(activityReadSource("diary/~h/s", "~h/f") != null)
        assertTrue(activityReadSource("heap/~h/s", "~h/f") != null)
    }

    @Test
    fun `activityReadAction wraps source under read all time null and is shallow`() {
        // Shallow by default: a deep read marks every thread under the
        // conversation read on the ship, which is not what opening a
        // chat means. Threads are read one at a time.
        val src = activityReadSource("~sampel")!!
        val body = activityReadAction(src)
        val read = body["read"]!!.jsonObject
        assertTrue(read["source"] is JsonObject)
        val all = read["action"]!!.jsonObject["all"]!!.jsonObject
        assertEquals(JsonNull, all["time"])
        assertEquals(false, all["deep"]!!.jsonPrimitive.content.toBoolean())
        val deep = activityReadAction(src, deep = true)["read"]!!.jsonObject["action"]!!.jsonObject["all"]!!.jsonObject
        assertEquals(true, deep["deep"]!!.jsonPrimitive.content.toBoolean())
    }

    // ─── parseActivityEventTarget — deep-link extraction ────────

    private fun obj(raw: String) = json.parseToJsonElement(raw).jsonObject

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

    // ─── sourceKeyToWhom — thread variants ──────────────────────

    @Test
    fun `thread source-key resolves to null (handled by sourceKeyToThreadSource)`() {
        // Used to return the underlying channel nest, but that
        // double-counted: the agent's channel/<nest> summary already
        // reflects the channel's activity, so adding the thread
        // summary's count on top inflated the badge (1 new reply →
        // channel showed 2). Thread events now flow only through
        // sourceKeyToThreadSource → ThreadUnreadEntity.
        assertNull(sourceKeyToWhom("thread/chat/~host/slug/170.141.184.507"))
        assertNull(sourceKeyToWhom("thread/chat/~host/slug/~author/170.141.184.507"))
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
    fun `diary and heap channels normalize like chat`() {
        assertEquals("170141", canonicalPostIdForWhom("diary/~h/s", "~a/170141"))
        assertEquals("170141", canonicalPostIdForWhom("heap/~h/s", "~a/170141"))
    }

}
