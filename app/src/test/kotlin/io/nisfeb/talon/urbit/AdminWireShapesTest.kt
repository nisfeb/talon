package io.nisfeb.talon.urbit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Snapshot tests for every admin `{group: {flag, a-group: {...}}}`
 * branch. If Tlon bumps the mark or renames a key, exactly one of
 * these fails with a concrete diff.
 *
 * The repo's admin pokes all delegate to these builders so a pass here
 * means the wire is right end-to-end.
 */
class AdminWireShapesTest {

    // ─── meta / delete ────────────────────────────────────────

    @Test
    fun `aGroupMetaUpdate shape matches tlon-apps`() {
        val body = aGroupMetaUpdate("T", "D", "I", "C")
        val meta = body["meta"]!!.jsonObject
        assertEquals("T", meta["title"]!!.jsonPrimitive.content)
        assertEquals("D", meta["description"]!!.jsonPrimitive.content)
        assertEquals("I", meta["image"]!!.jsonPrimitive.content)
        assertEquals("C", meta["cover"]!!.jsonPrimitive.content)
    }

    @Test
    fun `aGroupDelete is the bare delete null variant`() {
        val body = aGroupDelete()
        assertEquals(JsonNull, body["delete"])
    }

    // ─── seat / member ────────────────────────────────────────

    @Test
    fun `aGroupSeatDel wraps a ship as a-seat del`() {
        val body = aGroupSeatDel("~sampel")
        val seat = body["seat"]!!.jsonObject
        assertEquals(
            "~sampel",
            (seat["ships"] as JsonArray)[0].jsonPrimitive.content,
        )
        val aSeat = seat["a-seat"]!!.jsonObject
        assertEquals(JsonNull, aSeat["del"])
    }

    @Test
    fun `aGroupSeatDel accepts bare ship and adds tilde`() {
        val body = aGroupSeatDel("sampel")
        val ships = body["seat"]!!.jsonObject["ships"] as JsonArray
        assertEquals("~sampel", ships[0].jsonPrimitive.content)
    }

    @Test
    fun `aGroupSeatAddRole uses add-roles list`() {
        val body = aGroupSeatAddRole("~sampel", "admin")
        val aSeat = body["seat"]!!.jsonObject["a-seat"]!!.jsonObject
        val roles = aSeat["add-roles"] as JsonArray
        assertEquals("admin", roles[0].jsonPrimitive.content)
        assertFalse(aSeat.containsKey("del-roles"))
    }

    @Test
    fun `aGroupSeatDelRole uses del-roles list`() {
        val body = aGroupSeatDelRole("~sampel", "admin")
        val aSeat = body["seat"]!!.jsonObject["a-seat"]!!.jsonObject
        assertTrue(aSeat.containsKey("del-roles"))
        assertFalse(aSeat.containsKey("add-roles"))
    }

    // ─── entry.pending / entry.token ─────────────────────────

    @Test
    fun `aGroupPendingDel revokes a direct invite via a-pending del`() {
        val body = aGroupPendingDel("~guest")
        val pending = body["entry"]!!.jsonObject["pending"]!!.jsonObject
        val ships = pending["ships"] as JsonArray
        assertEquals("~guest", ships[0].jsonPrimitive.content)
        val aPending = pending["a-pending"]!!.jsonObject
        assertEquals(JsonNull, aPending["del"])
    }

    @Test
    fun `aGroupTokenDel revokes a token-based invite`() {
        val body = aGroupTokenDel("0v2.abc")
        val token = body["entry"]!!.jsonObject["token"]!!.jsonObject
        assertEquals("0v2.abc", token["del"]!!.jsonPrimitive.content)
    }

    // ─── entry.ask ───────────────────────────────────────────

    @Test
    fun `aGroupAskResolve approve uses a-ask string approve`() {
        val body = aGroupAskResolve("~guest", approve = true)
        val ask = body["entry"]!!.jsonObject["ask"]!!.jsonObject
        assertEquals("approve", ask["a-ask"]!!.jsonPrimitive.content)
    }

    @Test
    fun `aGroupAskResolve deny uses a-ask string deny`() {
        val body = aGroupAskResolve("~guest", approve = false)
        val ask = body["entry"]!!.jsonObject["ask"]!!.jsonObject
        assertEquals("deny", ask["a-ask"]!!.jsonPrimitive.content)
    }

    // ─── entry.ban ───────────────────────────────────────────

    @Test
    fun `aGroupBanAdd uses add-ships array`() {
        val body = aGroupBanAdd("~bad")
        val ban = body["entry"]!!.jsonObject["ban"]!!.jsonObject
        val ships = ban["add-ships"] as JsonArray
        assertEquals("~bad", ships[0].jsonPrimitive.content)
        assertFalse(ban.containsKey("del-ships"))
    }

    @Test
    fun `aGroupBanDel uses del-ships array`() {
        val body = aGroupBanDel("~reformed")
        val ban = body["entry"]!!.jsonObject["ban"]!!.jsonObject
        assertTrue(ban.containsKey("del-ships"))
        assertFalse(ban.containsKey("add-ships"))
    }

    // ─── top-level invite (not under a-group) ───────────────

    @Test
    fun `groupAction4InviteAdd puts invite at top level with a-invite null`() {
        val body = groupAction4InviteAdd("~host/flag", "~guest")
        // Sibling of `group`, never nested under `a-group`.
        assertFalse(body.containsKey("group"))
        val invite = body["invite"]!!.jsonObject
        assertEquals("~host/flag", invite["flag"]!!.jsonPrimitive.content)
        val ships = invite["ships"] as JsonArray
        assertEquals("~guest", ships[0].jsonPrimitive.content)
        val aInvite = invite["a-invite"]!!.jsonObject
        assertEquals(JsonNull, aInvite["token"])
        assertEquals(JsonNull, aInvite["note"])
    }

    // ─── group-action-4 envelope composition ────────────────

    @Test
    fun `groupAction4 wrapping each a-group diff produces a stable envelope`() {
        val inner = aGroupSeatDel("~ship")
        val wrapped = groupAction4("~host/flag", inner)
        val g = wrapped["group"]!!.jsonObject
        assertEquals("~host/flag", g["flag"]!!.jsonPrimitive.content)
        assertTrue(g["a-group"]!!.jsonObject.containsKey("seat"))
        // No group-action-3 legacy `update` envelope should leak back in.
        assertFalse(wrapped.containsKey("update"))
        assertFalse(g.containsKey("time"))
    }

    // ─── normalisePatp ─────────────────────────────────────

    @Test
    fun `normalisePatp adds tilde when missing`() {
        assertEquals("~sampel", normalisePatp("sampel"))
    }

    @Test
    fun `normalisePatp is idempotent on tilded ship`() {
        assertEquals("~sampel-palnet", normalisePatp("~sampel-palnet"))
    }
}
