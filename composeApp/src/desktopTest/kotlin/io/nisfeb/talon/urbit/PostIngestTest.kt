package io.nisfeb.talon.urbit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class PostIngestTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ─── happy path ─────────────────────────────────────────────

    @Test
    fun `post with reply + reacts fixture produces two messages and three reactions`() {
        val post = Fixtures.loadJson("channels/post-with-reply-and-reacts.json")
        val out = ingestedPost("chat/~host/slug", post)

        assertEquals(2, out.messages.size)
        val top = out.messages.first { it.parentId == null }
        assertEquals("170141184507933044937549665940933705728", top.id)
        assertEquals("~ricsul-bilwyt-dozzod-nisfeb", top.author)
        assertEquals(1777054902389L, top.sentMs)
        assertEquals("/chat", top.kind)

        val reply = out.messages.first { it.parentId != null }
        assertEquals(top.id, reply.parentId)
        assertEquals("170141184507933045432608980879542321152", reply.id)
        assertEquals("~sampel-palnet", reply.author)

        assertEquals(3, out.reactions.size)
        val byAuthor = out.reactions.groupBy { it.author }
        assertTrue(byAuthor.containsKey("~sampel-palnet"))
        assertTrue(byAuthor.containsKey("~ricsul-bilwyt-dozzod-nisfeb"))
    }

    @Test
    fun `diary essay fixture carries meta title and image on the entity`() {
        val post = Fixtures.loadJson("channels/diary-essay-with-meta.json")
        val out = ingestedPost("diary/~host/slug", post)
        assertEquals(1, out.messages.size)
        val m = out.messages[0]
        assertEquals("/diary", m.kind)
        assertEquals("First notebook post", m.title)
        assertEquals("https://example.com/cover.png", m.image)
    }

    // ─── tombstones ─────────────────────────────────────────────

    @Test
    fun `top-level tombstone yields a tombstone id and nothing else`() {
        val post = json.parseToJsonElement("""
            {"type":"tombstone","id":"170.141.184.507.933.044.937.549.665.940.933.705.728",
             "author":"~ricsul","deleted-at":1777055041699,"seq":961}
        """.trimIndent())
        val out = ingestedPost("chat/~host/slug", post)
        assertEquals(0, out.messages.size)
        assertEquals(0, out.reactions.size)
        assertEquals(1, out.tombstones.size)
        // Id is normalized to raw digits for DB lookup.
        assertEquals("170141184507933044937549665940933705728", out.tombstones[0])
    }

    @Test
    fun `nested reply tombstone is surfaced as a tombstone id`() {
        val post = json.parseToJsonElement("""
            {
              "seal": {"id":"1000","seq":1,"reacts":{},
                "replies": {
                  "0": {"type":"tombstone","id":"2.000","deleted-at":0,"seq":2,"author":"~x"}
                }},
              "essay": {"content":[],"author":"~x","sent":0,"kind":"/chat","blob":null,"meta":null}
            }
        """.trimIndent())
        val out = ingestedPost("chat/~host/slug", post)
        assertEquals(1, out.messages.size)
        assertEquals(listOf("2000"), out.tombstones)
    }

    // ─── id normalization ──────────────────────────────────────

    @Test
    fun `dotted seal id is normalized to undotted in the entity`() {
        val post = json.parseToJsonElement("""
            {
              "seal": {"id":"170.141.184.507.933.044.937.549.665.940.933.705.728",
                       "reacts":{},"replies":{},"seq":1},
              "essay": {"content":[],"author":"~x","sent":0,"kind":"/chat","blob":null,"meta":null}
            }
        """.trimIndent())
        val out = ingestedPost("chat/~host/slug", post)
        assertEquals("170141184507933044937549665940933705728", out.messages[0].id)
    }

    // ─── malformed input ──────────────────────────────────────

    @Test
    fun `missing essay yields an empty ingest`() {
        val post = json.parseToJsonElement("""
            {"seal":{"id":"1","reacts":{},"replies":{},"seq":1}}
        """.trimIndent())
        val out = ingestedPost("chat/~host/slug", post)
        assertEquals(0, out.messages.size)
        assertEquals(0, out.reactions.size)
        assertEquals(0, out.tombstones.size)
    }

    @Test
    fun `non-object post yields an empty ingest`() {
        val out = ingestedPost("chat/~host/slug", JsonPrimitive("garbage"))
        assertEquals(0, out.messages.size)
    }

    // ─── mergeBlobIntoContent ─────────────────────────────────

    @Test
    fun `mergeBlobIntoContent returns content unchanged when blob is null`() {
        val content = buildJsonArray { }
        val merged = mergeBlobIntoContent(content, null)
        assertEquals(content, merged)
    }

    @Test
    fun `mergeBlobIntoContent prepends a file block for each file entry`() {
        val content = buildJsonArray {
            add(buildJsonObject { put("inline", buildJsonArray { add(JsonPrimitive("hi")) }) })
        }
        val blob = JsonPrimitive("""
            [{"type":"file","fileUri":"https://x/y.png","name":"y.png","size":1024,"mimeType":"image/png"}]
        """.trimIndent().trim())
        val merged = mergeBlobIntoContent(content, blob).jsonArray
        // File block first, original inline verse second.
        assertEquals(2, merged.size)
        val fileBlock = merged[0].jsonObject["block"]!!.jsonObject["file"]!!.jsonObject
        assertEquals("https://x/y.png", fileBlock["url"]!!.jsonPrimitive.content)
        assertEquals("y.png", fileBlock["name"]!!.jsonPrimitive.content)
        assertEquals("1024", fileBlock["size"]!!.jsonPrimitive.content)
        assertEquals("image/png", fileBlock["mime"]!!.jsonPrimitive.content)
    }

    @Test
    fun `mergeBlobIntoContent ignores non-file entries and malformed blobs`() {
        val content = buildJsonArray { }
        // Non-file type.
        val nonFile = JsonPrimitive("""[{"type":"other","url":"x"}]""")
        assertEquals(content, mergeBlobIntoContent(content, nonFile))
        // Not JSON.
        val nonsense = JsonPrimitive("not-json")
        assertEquals(content, mergeBlobIntoContent(content, nonsense))
        // Empty array.
        val emptyArr = JsonPrimitive("[]")
        assertEquals(content, mergeBlobIntoContent(content, emptyArr))
    }

    // ─── pureEntity / pureReplyEntity directly ───────────────

    @Test
    fun `pureEntity fills missing fields with sensible defaults`() {
        val essay = buildJsonObject { }
        val e = pureEntity("chat/~x/y", "12345", essay)
        assertEquals("", e.author)
        assertEquals(0L, e.sentMs)
        assertEquals("/chat", e.kind)
        assertNull(e.title)
        assertNull(e.image)
    }

    @Test
    fun `pureReplyEntity sets parentId and kind chat`() {
        val essay = buildJsonObject {
            put("author", "~x")
            put("sent", 1L)
            put("content", buildJsonArray { })
        }
        val e = pureReplyEntity("chat/~x/y", "parent123", "reply456", essay)
        assertEquals("parent123", e.parentId)
        assertEquals("/chat", e.kind)
        assertEquals("~x", e.author)
        assertEquals(1L, e.sentMs)
    }
}
