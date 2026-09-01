package io.nisfeb.talon.call

import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wire 5: role-gated party lines. The token's permissions decide
 * whether the client even opens a mic, and the three role actions
 * mirror lib/trunk-json.hoon — same drift risk as every other shape.
 */
class TrunkWireRolesTest {

    /** A real-shaped (unsigned) HS256 token: base64url, no padding. */
    private fun token(payload: String): String {
        val enc = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        val header = enc.encode("""{"alg":"HS256","typ":"JWT"}""".encodeToByteArray())
        val body = enc.encode(payload.encodeToByteArray())
        val sig = enc.encode(ByteArray(32) { (it * 7).toByte() })
        return "$header.$body.$sig"
    }

    @Test
    fun jwtPermissionsReadsTheClaim() {
        // The junk claim's ">>>???" bytes force '-' and '_' into the
        // encoding, so this fixture genuinely exercises the base64url
        // alphabet — a standard-base64 decoder fails it.
        val t = token(
            """{"sub":"~zod","aud":["https://sfu/group/talon/x"],""" +
                """"permissions":["op","present","message"],"junk":">>>???"}""",
        )
        val payload = t.split('.')[1]
        assertTrue(payload.contains('-') && payload.contains('_'), "fixture must use -_ chars")
        assertTrue(!t.contains('='), "fixture must be unpadded")
        assertEquals(setOf("op", "present", "message"), TrunkWire.jwtPermissions(t))
    }

    @Test
    fun aListenerTokenHasNoPresent() {
        val t = token("""{"sub":"~zod","permissions":["message"]}""")
        assertEquals(setOf("message"), TrunkWire.jwtPermissions(t))
    }

    @Test
    fun anythingUnreadableIsTheLegacySpeakerPair() {
        // Old hosts mint tokens with no permissions claim; anything
        // else broken lands the same way. Failing closed would mute
        // every member of every pre-roles line.
        val legacy = setOf("present", "message")
        assertEquals(legacy, TrunkWire.jwtPermissions(""))
        assertEquals(legacy, TrunkWire.jwtPermissions("not-a-jwt"))
        assertEquals(legacy, TrunkWire.jwtPermissions("a.!!!.c"))
        assertEquals(legacy, TrunkWire.jwtPermissions(token("""{"sub":"~zod"}""")))
        assertEquals(legacy, TrunkWire.jwtPermissions(token("not json at all")))
    }

    // ── action shapes ─────────────────────────────────────────────
    // Every key must be present: the agent's ot nacks a missing one.

    @Test
    fun setRoomAccessActionShape() {
        val body = TrunkWire.setRoomAccessAction("~zod", "lounge", null, listOf("crew"))
            .jsonObject["set-room-access"]!!.jsonObject
        assertEquals(setOf("host", "name", "join", "speak"), body.keys)
        assertEquals("~zod", body["host"]!!.jsonPrimitive.content)
        assertEquals("lounge", body["name"]!!.jsonPrimitive.content)
        assertTrue(body["join"] is JsonNull, "everyone-may-join is JSON null, not absent")
        assertEquals(listOf("crew"), body["speak"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun moderateMemberActionShape() {
        val body = TrunkWire.moderateMemberAction("~zod", "lounge", "~bus", true)
            .jsonObject["moderate-member"]!!.jsonObject
        assertEquals(setOf("host", "name", "who", "mute"), body.keys)
        assertEquals("~bus", body["who"]!!.jsonPrimitive.content)
        assertEquals("true", body["mute"]!!.jsonPrimitive.content)
    }

    @Test
    fun getRoomAccessActionShape() {
        val body = TrunkWire.getRoomAccessAction("~zod", "lounge")
            .jsonObject["get-room-access"]!!.jsonObject
        assertEquals(setOf("host", "name"), body.keys)
    }

    // ── parsing ───────────────────────────────────────────────────

    @Test
    fun accessStateFactParses() {
        val up = TrunkWire.parseUpdate(
            Json.parseToJsonElement(
                """{"access-state":{"from":"~zod","name":"lounge",""" +
                    """"join":null,"speak":["crew"],"muted":["~bus"]}}""",
            ),
        ) as TrunkUpdate.AccessState
        assertEquals("~zod", up.from)
        assertEquals("lounge", up.name)
        assertEquals(RoomAccess(null, listOf("crew"), listOf("~bus")), up.access)
    }

    @Test
    fun aNamelessAccessStateIsJunk() {
        assertNull(
            TrunkWire.parseUpdate(
                Json.parseToJsonElement("""{"access-state":{"from":"~zod"}}"""),
            ),
        )
    }

    @Test
    fun roomsScryCarriesTheGates() {
        val rooms = TrunkWire.parseRooms(
            Json.parseToJsonElement(
                """[{"name":"a","title":"A","listen":false,"sfu-base":"",""" +
                    """"custom-sfu":false,"members":[],"admins":[],"group":null,""" +
                    """"join-roles":null,"speak-roles":["crew"],"muted":["~bus"]}]""",
            ),
        )
        val room = rooms.single()
        assertNull(room.joinRoles)
        assertEquals(listOf("crew"), room.speakRoles)
        assertEquals(setOf("~bus"), room.muted)
    }

    @Test
    fun aWire4ShipsRoomsStillParse() {
        // No role keys at all — every ship before wire 5. Must read
        // as ungated, not fail.
        val rooms = TrunkWire.parseRooms(
            Json.parseToJsonElement(
                """[{"name":"old","title":"O","listen":false,"sfu-base":"",""" +
                    """"custom-sfu":false,"members":["~nec"],"admins":[]}]""",
            ),
        )
        val room = rooms.single()
        assertNull(room.joinRoles)
        assertNull(room.speakRoles)
        assertEquals(emptySet(), room.muted)
    }
}
