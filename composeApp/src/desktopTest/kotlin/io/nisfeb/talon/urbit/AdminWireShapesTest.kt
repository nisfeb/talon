package io.nisfeb.talon.urbit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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

    // ─── channels ─────────────────────────────────────────────
    // Checked against tlon-apps' parsers: groups-json dejs v7 a-channel
    // (add-readers/del-readers take role ids, edit takes the whole
    // channel with every key, del takes null) and channel-json dejs v10
    // a-channel (add-writers/del-writers take role ids, as %tas).

    private val general = AdminChannel(
        nest = "chat/~zod/general", title = "General", description = "talk", image = "", cover = "#abcdef",
        addedMs = 1_700_000_000_000, section = "default", readers = setOf("gardener"), join = true, editable = true,
    )

    @Test
    fun `a channel's readers go to the group, wrapped for group-action-4`() {
        assertEquals(
            """{"group":{"flag":"~zod/garden","a-group":{"channel":{"nest":"chat/~zod/general","a-channel":{"add-readers":["admin","gardener"]}}}}}""",
            groupAction4("~zod/garden", aGroupChannel("chat/~zod/general", aChannelReaders(true, setOf("gardener", "admin")))).toString(),
        )
        assertEquals("""{"del-readers":["admin"]}""", aChannelReaders(false, setOf("admin")).toString())
    }

    @Test
    fun `a channel edit sends every key, the rest as the record had it`() {
        assertEquals(
            """{"edit":{"meta":{"title":"Chat","description":"all of it","image":"","cover":"#abcdef"},"added":1700000000000,"section":"default","readers":["gardener"],"join":true}}""",
            aChannelEdit(general, "Chat", "all of it").toString(),
        )
    }

    @Test
    fun `a channel delete is del null`() {
        assertEquals("""{"del":null}""", aChannelDelete().toString())
    }

    @Test
    fun `who may post goes to channels as channel-action-2`() {
        assertEquals(
            """{"channel":{"nest":"chat/~zod/general","action":{"add-writers":["admin"]}}}""",
            channelWriters("chat/~zod/general", true, setOf("admin")).toString(),
        )
        assertEquals(
            """{"channel":{"nest":"chat/~zod/general","action":{"del-writers":["admin","gardener"]}}}""",
            channelWriters("chat/~zod/general", false, setOf("gardener", "admin")).toString(),
        )
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
    fun `aGroupAskResolve says approve or deny in a-ask`() {
        for ((approve, word) in listOf(true to "approve", false to "deny")) {
            val ask = aGroupAskResolve("~guest", approve)["entry"]!!.jsonObject["ask"]!!.jsonObject
            assertEquals(word, ask["a-ask"]!!.jsonPrimitive.content)
        }
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

}
