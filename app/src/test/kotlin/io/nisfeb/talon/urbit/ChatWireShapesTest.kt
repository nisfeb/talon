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

    // ─── delete optimistic-local rule ──────────────────────────

    @Test
    fun `whomNeedsOptimisticDelete is true for DMs and clubs`() {
        // DMs and clubs ride chat-dm-action-2 / chat-club-action-2
        // whose delete echo isn't reliable across mark drift — repo
        // applies the local soft-delete immediately so a hiccup in
        // the SSE round-trip can't leave a "deleted" message stuck
        // on screen. This rule has regressed twice already.
        assertTrue(whomNeedsOptimisticDelete("~sampel-palnet"))
        assertTrue(whomNeedsOptimisticDelete("~ricsul-bilwyt-dozzod-nisfeb"))
        assertTrue(whomNeedsOptimisticDelete("0v4.abcde"))
    }

    @Test
    fun `whomNeedsOptimisticDelete is false for channels`() {
        // Channels go through channel-action-2 whose r-post.set-with-
        // tombstone echo we trust to drive local state. Don't
        // optimistic-delete there — the server enforces admin rights
        // and we'd hide a message for the user even when the poke
        // gets nacked for permission.
        assertFalse(whomNeedsOptimisticDelete("chat/~host/slug"))
        assertFalse(whomNeedsOptimisticDelete("diary/~host/slug"))
        assertFalse(whomNeedsOptimisticDelete("heap/~host/slug"))
    }

    @Test
    fun `dm delete poke includes the postId in diff`() {
        // Locks the on-the-wire shape so a chat-dm-action mark bump
        // (or accidental rename) surfaces here loudly.
        val body = dmAction(
            peer = "~sampel",
            postId = "~ricsul/9.999",
            delta = writsDelDelta(),
        )
        val diff = body["diff"]!!.jsonObject
        assertEquals("~ricsul/9.999", diff["id"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, diff["delta"]!!.jsonObject["del"])
    }

    @Test
    fun `club delete poke nests the writ-id under writ delta`() {
        val body = clubAction(
            clubId = "0v4.abcde",
            postId = "~ricsul/9.999",
            delta = writsDelDelta(),
        )
        val writ = body["diff"]!!.jsonObject["delta"]!!.jsonObject["writ"]!!.jsonObject
        assertEquals("~ricsul/9.999", writ["id"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, writ["delta"]!!.jsonObject["del"])
    }

    // ─── redotWritId — egress @da dotting ──────────────────────

    @Test
    fun `redotWritId dot-groups the @da of an undotted writ id`() {
        // DB keeps writ ids undotted — this is the rule we depend on
        // for the SSE-vs-paginate dedupe. The %chat agent's poke dejs
        // (slav %ud → dem:ag) only accepts dotted decimals though, so
        // egress must convert. Without this, every DM/club delete +
        // react was silently NACK'd by the server.
        assertEquals(
            "~ricsul-bilwyt-dozzod-nisfeb/170.141.184.507.933.044.937.549.665.940.933.705.728",
            redotWritId(
                "~ricsul-bilwyt-dozzod-nisfeb/170141184507933044937549665940933705728",
            ),
        )
    }

    @Test
    fun `redotWritId is a no-op on already-dotted writ ids`() {
        // Idempotent — re-dotting a dotted id returns the same string
        // so layered helpers can't double-mangle.
        assertEquals("~ricsul/9.999", redotWritId("~ricsul/9.999"))
    }

    @Test
    fun `redotWritId leaves short author-prefixed ids alone`() {
        // Tests an edge case: DB occasionally yields an id with a
        // small @ud (under 1000) that doesn't need grouping. Should
        // pass through unchanged.
        assertEquals("~ricsul/9", redotWritId("~ricsul/9"))
    }

    @Test
    fun `redotWritId handles an unprefixed bare da gracefully`() {
        // Channel posts wear no `~author/` prefix. The helper still
        // dots them so it's safe to use uniformly. (For channels we
        // already dot via dotAtom at the call site, but defensive
        // dotting here makes redotWritId reusable.)
        assertEquals(
            "170.141.184.507",
            redotWritId("170141184507"),
        )
    }

    @Test
    fun `dm delete poke goes out with a dotted writ id`() {
        // End-to-end through dmAction: undotted DB form goes in,
        // dotted wire form comes out. Locks the invariant.
        val body = dmAction(
            peer = "~sampel",
            postId = "~ricsul/170141184507933044937549665940933705728",
            delta = writsDelDelta(),
        )
        val diff = body["diff"]!!.jsonObject
        assertEquals(
            "~ricsul/170.141.184.507.933.044.937.549.665.940.933.705.728",
            diff["id"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `club delete poke goes out with a dotted writ id`() {
        val body = clubAction(
            clubId = "0v4.abcde",
            postId = "~ricsul/170141184507933044937549665940933705728",
            delta = writsDelDelta(),
        )
        val writ = body["diff"]!!.jsonObject["delta"]!!.jsonObject["writ"]!!.jsonObject
        assertEquals(
            "~ricsul/170.141.184.507.933.044.937.549.665.940.933.705.728",
            writ["id"]!!.jsonPrimitive.content,
        )
    }

    // ─── every writ-id egress path must dot ────────────────────

    @Test
    fun `replyDelta dots the reply id`() {
        // The reply-add delta carries a writ id under the inner
        // `reply.id`. Same rule as the outer envelopes.
        val essay = buildJsonObject { put("content", buildJsonArray { }) }
        val out = replyDelta(
            replyId = "~author/170141184507933044937549665940933705728",
            replyEssay = essay,
        )
        val reply = out["reply"]!!.jsonObject
        assertEquals(
            "~author/170.141.184.507.933.044.937.549.665.940.933.705.728",
            reply["id"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `dm reply-add dots both parent and inner reply ids`() {
        // End-to-end: dmAction (parent id) + replyDelta (reply id).
        // Both must be dotted; if either escapes undotted, the
        // chat-dm-action-2 dejs NACKs the poke.
        val essay = buildJsonObject { put("content", buildJsonArray { }) }
        val body = dmAction(
            peer = "~sampel",
            postId = "~parent/170141184507", // outer parent
            delta = replyDelta(
                replyId = "~author/170141184507933044937549665940933705728",
                replyEssay = essay,
            ),
        )
        val diff = body["diff"]!!.jsonObject
        assertEquals("~parent/170.141.184.507", diff["id"]!!.jsonPrimitive.content)
        val inner = diff["delta"]!!.jsonObject["reply"]!!.jsonObject
        assertEquals(
            "~author/170.141.184.507.933.044.937.549.665.940.933.705.728",
            inner["id"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `club reply-add dots both parent and inner reply ids`() {
        val essay = buildJsonObject { put("content", buildJsonArray { }) }
        val body = clubAction(
            clubId = "0v4.abcde",
            postId = "~parent/170141184507",
            delta = replyDelta(
                replyId = "~author/170141184507933044937549665940933705728",
                replyEssay = essay,
            ),
        )
        val writ = body["diff"]!!.jsonObject["delta"]!!.jsonObject["writ"]!!.jsonObject
        assertEquals("~parent/170.141.184.507", writ["id"]!!.jsonPrimitive.content)
        val reply = writ["delta"]!!.jsonObject["reply"]!!.jsonObject
        assertEquals(
            "~author/170.141.184.507.933.044.937.549.665.940.933.705.728",
            reply["id"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `dm post-add carries a dotted writ id`() {
        // Posting a fresh DM also goes through dmAction (with a
        // writsAddDelta inside). Locks the egress dot rule on the
        // send path — without this, freshly-sent DMs from Talon
        // never reached the peer.
        val essay = buildJsonObject { put("content", buildJsonArray { }) }
        val body = dmAction(
            peer = "~sampel",
            postId = "~me/170141184507933044937549665940933705728",
            delta = writsAddDelta(essay),
        )
        assertEquals(
            "~me/170.141.184.507.933.044.937.549.665.940.933.705.728",
            body["diff"]!!.jsonObject["id"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `dm add-react carries a dotted writ id`() {
        val body = dmAction(
            peer = "~sampel",
            postId = "~author/170141184507933044937549665940933705728",
            delta = writsAddReactDelta("~me", ":+1:"),
        )
        assertEquals(
            "~author/170.141.184.507.933.044.937.549.665.940.933.705.728",
            body["diff"]!!.jsonObject["id"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `dm del-react carries a dotted writ id`() {
        val body = dmAction(
            peer = "~sampel",
            postId = "~author/170141184507933044937549665940933705728",
            delta = writsDelReactDelta("~me"),
        )
        assertEquals(
            "~author/170.141.184.507.933.044.937.549.665.940.933.705.728",
            body["diff"]!!.jsonObject["id"]!!.jsonPrimitive.content,
        )
    }
}
