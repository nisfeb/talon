package io.nisfeb.talon.urbit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exact-shape assertions for every wire builder. When the server's
 * action schema changes, one of these tests fails loudly instead of
 * the app silently losing a feature.
 */
class WireShapesTest {

    private val json = Json { prettyPrint = false }

    private fun canonical(o: JsonObject): String =
        // Re-parse to re-sort keys alphabetically — JsonObject preserves
        // insertion order but two semantically-equal objects may differ
        // in key order. Using `.toString()` is fine for our asserts
        // since builders use consistent insertion order.
        o.toString()

    // ─── DM / club envelopes ────────────────────────────────────

    @Test
    fun `dmAction wraps with ship and diff`() {
        val out = dmAction(
            peer = "~sampel-palnet",
            postId = "~author/1.234",
            delta = buildJsonObject { put("add", JsonNull) },
        )
        assertEquals("~sampel-palnet", out["ship"]!!.jsonPrimitive.content)
        val diff = out["diff"]!!.jsonObject
        assertEquals("~author/1.234", diff["id"]!!.jsonPrimitive.content)
        assertTrue(diff["delta"]!!.jsonObject.containsKey("add"))
    }

    @Test
    fun `clubAction includes uid sentinel and nested writ`() {
        val out = clubAction(
            clubId = "0vabc",
            postId = "~author/1.234",
            delta = buildJsonObject { put("add", JsonNull) },
        )
        assertEquals("0vabc", out["id"]!!.jsonPrimitive.content)
        val diff = out["diff"]!!.jsonObject
        assertEquals("0v4", diff["uid"]!!.jsonPrimitive.content)
        val writ = diff["delta"]!!.jsonObject["writ"]!!.jsonObject
        assertEquals("~author/1.234", writ["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `channelAction wraps under nest and action`() {
        val out = channelAction(
            nest = "chat/~host/slug",
            action = buildJsonObject { put("post", JsonNull) },
        )
        val ch = out["channel"]!!.jsonObject
        assertEquals("chat/~host/slug", ch["nest"]!!.jsonPrimitive.content)
        assertTrue(ch["action"]!!.jsonObject.containsKey("post"))
    }

    // ─── reply deltas ────────────────────────────────────────────

    @Test
    fun `replyDelta wraps reply-essay under delta add`() {
        val essay = buildJsonObject { put("content", JsonArray(emptyList())) }
        val out = replyDelta(replyId = "~author/9.999", replyEssay = essay)
        val reply = out["reply"]!!.jsonObject
        assertEquals("~author/9.999", reply["id"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, reply["meta"])
        val add = reply["delta"]!!.jsonObject["add"]!!.jsonObject
        assertTrue(add["reply-essay"]!!.jsonObject === essay ||
            add["reply-essay"]!!.jsonObject.toString() == essay.toString())
        assertEquals(JsonNull, add["time"])
    }

    // ─── channel post action shapes ─────────────────────────────

    @Test
    fun `channelReplyAdd dots the parent id`() {
        val essay = buildJsonObject { put("content", JsonArray(emptyList())) }
        val out = channelReplyAdd(
            parentId = "170141184507932790143209384169177088000",
            replyEssay = essay,
        )
        val reply = out["post"]!!.jsonObject["reply"]!!.jsonObject
        assertEquals(
            "170.141.184.507.932.790.143.209.384.169.177.088.000",
            reply["id"]!!.jsonPrimitive.content,
        )
        assertTrue(reply["action"]!!.jsonObject.containsKey("add"))
    }

    @Test
    fun `channelPostDelete uses del key and dots the id`() {
        val out = channelPostDelete(
            postId = "170141184507932790143209384169177088000",
        )
        val post = out["post"]!!.jsonObject
        // Regression: the shape is `{post: {del: id}}` — earlier we
        // tried `{post: {id, u-post: {set: null}}}` which the current
        // %channels mark silently ignores.
        assertTrue(post.containsKey("del"))
        assertTrue(!post.containsKey("u-post"))
        assertTrue(!post.containsKey("id"))
        assertEquals(
            "170.141.184.507.932.790.143.209.384.169.177.088.000",
            post["del"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `channelReplyDelete dots both parent and target ids`() {
        val out = channelReplyDelete(
            parentId = "1234567890",
            postId = "9876543210",
        )
        val reply = out["post"]!!.jsonObject["reply"]!!.jsonObject
        assertEquals("1.234.567.890", reply["id"]!!.jsonPrimitive.content)
        val del = reply["action"]!!.jsonObject["del"]!!.jsonPrimitive.content
        assertEquals("9.876.543.210", del)
    }

    @Test
    fun `channelAddReact dots the id and copies author and react`() {
        val out = channelAddReact(
            postId = "170141184507932790143209384169177088000",
            author = "~ricsul-bilwyt-dozzod-nisfeb",
            emoji = ":fire:",
        )
        val r = out["post"]!!.jsonObject["add-react"]!!.jsonObject
        assertEquals(
            "170.141.184.507.932.790.143.209.384.169.177.088.000",
            r["id"]!!.jsonPrimitive.content,
        )
        assertEquals("~ricsul-bilwyt-dozzod-nisfeb", r["author"]!!.jsonPrimitive.content)
        assertEquals(":fire:", r["react"]!!.jsonPrimitive.content)
    }

    @Test
    fun `channelDelReact dots the id and omits react field`() {
        val out = channelDelReact(
            postId = "1234567890",
            author = "~ricsul",
        )
        val r = out["post"]!!.jsonObject["del-react"]!!.jsonObject
        assertEquals("1.234.567.890", r["id"]!!.jsonPrimitive.content)
        assertEquals("~ricsul", r["author"]!!.jsonPrimitive.content)
        assertTrue(!r.containsKey("react"))
    }

    // ─── group-action-4 ────────────────────────────────────────

    @Test
    fun `groupAction4 produces {group {flag, a-group}}`() {
        val diff = buildJsonObject {
            put("seat", buildJsonObject {
                put("ships", JsonArray(emptyList()))
                put("a-seat", buildJsonObject { put("del", JsonNull) })
            })
        }
        val out = groupAction4("~host/slug", diff)
        val g = out["group"]!!.jsonObject
        assertEquals("~host/slug", g["flag"]!!.jsonPrimitive.content)
        assertTrue(g["a-group"]!!.jsonObject.containsKey("seat"))
        // No legacy `update`/`time` envelope anymore.
        assertTrue(!out.containsKey("update"))
        assertNull(out["time"])
    }

    // ─── essay ─────────────────────────────────────────────────

    @Test
    fun `buildEssay for chat omits meta`() {
        val essay = buildEssay(
            content = JsonArray(emptyList()),
            author = "~sampel",
            sentMs = 1_700_000_000_000L,
        )
        assertEquals("/chat", essay["kind"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, essay["meta"])
        assertEquals(JsonNull, essay["blob"])
        assertEquals("~sampel", essay["author"]!!.jsonPrimitive.content)
        assertEquals(1_700_000_000_000L, essay["sent"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `buildEssay for notebook includes meta`() {
        val essay = buildEssay(
            content = JsonArray(emptyList()),
            author = "~sampel",
            sentMs = 0L,
            kind = "/diary",
            meta = notebookMeta(title = "Hello", image = "https://x/y.png"),
        )
        assertEquals("/diary", essay["kind"]!!.jsonPrimitive.content)
        val meta = essay["meta"]!!.jsonObject
        assertEquals("Hello", meta["title"]!!.jsonPrimitive.content)
        assertEquals("https://x/y.png", meta["image"]!!.jsonPrimitive.content)
        assertEquals("", meta["description"]!!.jsonPrimitive.content)
        assertEquals("", meta["cover"]!!.jsonPrimitive.content)
    }

    @Test
    fun `buildEssay for gallery uses heap kind`() {
        val essay = buildEssay(
            content = JsonArray(emptyList()),
            author = "~sampel",
            sentMs = 0L,
            kind = "/heap",
        )
        assertEquals("/heap", essay["kind"]!!.jsonPrimitive.content)
    }

    // ─── gallery helpers ───────────────────────────────────────

    @Test
    fun `galleryLinkBlock wraps a link under block, not inline`() {
        val v = galleryLinkBlock("https://example.com")
        // Regression: we previously posted a plain URL as an inline,
        // which prevented server-side link enrichment.
        assertTrue(v.containsKey("block"))
        val link = v["block"]!!.jsonObject["link"]!!.jsonObject
        assertEquals("https://example.com", link["url"]!!.jsonPrimitive.content)
        assertTrue(link["meta"]!!.jsonObject.isEmpty())
    }

    // ─── end-to-end composition (what the actual poke payload looks like) ───

    @Test
    fun `end-to-end channel reply payload`() {
        val essay = buildEssay(
            content = buildJsonArray {
                add(buildJsonObject { put("inline", buildJsonArray { add(JsonPrimitive("hi")) }) })
            },
            author = "~ricsul",
            sentMs = 1_700_000_000_000L,
        )
        val payload = channelAction(
            nest = "chat/~host/slug",
            action = channelReplyAdd(
                parentId = "170141184507932790143209384169177088000",
                replyEssay = essay,
            ),
        )
        // Drill down to the id that used to nack:
        val id = payload["channel"]!!.jsonObject["action"]!!.jsonObject["post"]!!
            .jsonObject["reply"]!!.jsonObject["id"]!!.jsonPrimitive.content
        assertEquals(
            "170.141.184.507.932.790.143.209.384.169.177.088.000",
            id,
        )
    }
}
