package io.nisfeb.talon.urbit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * %chat (DM + club) and %groups lifecycle poke shapes.
 *
 * Covers the *contents* of the writ / club `delta` envelopes — the
 * outer `{ship, diff: {id, delta}}` and `{id, diff: {uid, delta}}`
 * wrappers are already tested in WireShapesTest; here we pin down
 * what goes INSIDE delta so a `chat-dm-action-2` → `-3` bump surfaces
 * immediately.
 */
class ChatWireShapesTest {

    // ─── writs delta internals ─────────────────────────────────

    @Test
    fun `writsAddDelta carries essay under add with null time`() {
        val essay = buildJsonObject { put("content", buildJsonArray { }) }
        val out = writsAddDelta(essay)
        val add = out["add"]!!.jsonObject
        assertTrue(add.containsKey("essay"))
        assertEquals(JsonNull, add["time"])
    }

    @Test
    fun `writsDelDelta is the bare del null variant`() {
        val out = writsDelDelta()
        assertEquals(JsonNull, out["del"])
        assertFalse(out.containsKey("add"))
    }

    @Test
    fun `writsAddReactDelta matches tlon shape`() {
        val out = writsAddReactDelta("~sampel", ":fire:")
        val add = out["add-react"]!!.jsonObject
        assertEquals("~sampel", add["author"]!!.jsonPrimitive.content)
        assertEquals(":fire:", add["react"]!!.jsonPrimitive.content)
    }

    @Test
    fun `writsDelReactDelta is {del-react author}`() {
        val out = writsDelReactDelta("~sampel")
        assertEquals("~sampel", out["del-react"]!!.jsonPrimitive.content)
    }

    // ─── complete DM poke body ────────────────────────────────

    @Test
    fun `dm add-essay poke carries ship + writ delta`() {
        val essay = buildJsonObject {
            put("content", buildJsonArray { })
            put("author", "~ricsul")
            put("sent", 0L)
        }
        val body = dmAction(
            peer = "~sampel",
            postId = "~ricsul/1.234",
            delta = writsAddDelta(essay),
        )
        assertEquals("~sampel", body["ship"]!!.jsonPrimitive.content)
        val diff = body["diff"]!!.jsonObject
        assertEquals("~ricsul/1.234", diff["id"]!!.jsonPrimitive.content)
        val add = diff["delta"]!!.jsonObject["add"]!!.jsonObject
        assertTrue(add.containsKey("essay"))
    }

    @Test
    fun `dm delete poke carries ship + del delta`() {
        val body = dmAction(
            peer = "~sampel",
            postId = "~ricsul/9.999",
            delta = writsDelDelta(),
        )
        assertEquals(JsonNull, body["diff"]!!.jsonObject["delta"]!!.jsonObject["del"])
    }

    // ─── club body ────────────────────────────────────────────

    @Test
    fun `club add-react poke nests under writ delta`() {
        val body = clubAction(
            clubId = "0v2.abcde",
            postId = "~x/1.000",
            delta = writsAddReactDelta("~x", ":+1:"),
        )
        val diff = body["diff"]!!.jsonObject
        // Known-good uid sentinel.
        assertEquals("0v4", diff["uid"]!!.jsonPrimitive.content)
        val writ = diff["delta"]!!.jsonObject["writ"]!!.jsonObject
        assertEquals("~x/1.000", writ["id"]!!.jsonPrimitive.content)
        assertTrue(writ["delta"]!!.jsonObject.containsKey("add-react"))
    }

    // ─── reply-add via replyDelta ────────────────────────────

    @Test
    fun `reply-add in a DM nests reply-essay under delta add`() {
        val replyEssay = buildJsonObject {
            put("content", buildJsonArray { })
            put("author", "~ricsul")
            put("sent", 0L)
        }
        val delta = replyDelta("~ricsul/9.999", replyEssay)
        val body = dmAction(
            peer = "~sampel",
            postId = "~ricsul/1.234",
            delta = delta,
        )
        val reply = body["diff"]!!.jsonObject["delta"]!!.jsonObject["reply"]!!.jsonObject
        assertEquals("~ricsul/9.999", reply["id"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, reply["meta"])
        val add = reply["delta"]!!.jsonObject["add"]!!.jsonObject
        assertTrue(add.containsKey("reply-essay"))
        assertEquals(JsonNull, add["time"])
    }

    // ─── %groups lifecycle pokes ────────────────────────────

    @Test
    fun `groupJoinPayload shape is {flag, join-all true}`() {
        val out = groupJoinPayload("~host/flag")
        assertEquals("~host/flag", out["flag"]!!.jsonPrimitive.content)
        assertEquals(
            "true",
            out["join-all"]!!.jsonPrimitive.content,
        )
    }
}
