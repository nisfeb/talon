package io.nisfeb.talon.call

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Wire shapes must match lib/trunk-json.hoon in gwbtc/trunk. */
class TrunkWireTest {

    @Test
    fun sendActionShape() {
        val el = TrunkWire.sendAction("~litzod", TrunkSig.Offer("c1", "v=0\r\n", "sha-256 AA:BB"))
        val send = el.jsonObject["send"]!!.jsonObject
        assertEquals("~litzod", send["ship"].toString().trim('"'))
        val offer = send["sig"]!!.jsonObject["offer"]!!.jsonObject
        assertEquals("c1", offer["id"].toString().trim('"'))
    }

    @Test
    fun updateRoundTripsEveryVariant() {
        val cases = listOf(
            """{"recv":{"from":"~zod","sig":{"ring":{"id":"a"}}}}""" to TrunkSig.Ring("a"),
            """{"recv":{"from":"~zod","sig":{"offer":{"id":"a","sdp":"s","fpr":"f"}}}}""" to
                TrunkSig.Offer("a", "s", "f"),
            """{"recv":{"from":"~zod","sig":{"accept":{"id":"a","sdp":"s","fpr":"f"}}}}""" to
                TrunkSig.Accept("a", "s", "f"),
            """{"recv":{"from":"~zod","sig":{"reject":{"id":"a","reason":"busy"}}}}""" to
                TrunkSig.Reject("a", "busy"),
            """{"recv":{"from":"~zod","sig":{"hangup":{"id":"a"}}}}""" to TrunkSig.Hangup("a"),
        )
        for ((raw, expected) in cases) {
            val recv = TrunkWire.parseUpdate(Json.parseToJsonElement(raw)) as TrunkUpdate.Recv
            assertEquals("~zod", recv.from)
            assertEquals(expected, recv.sig)
        }
    }

    @Test
    fun junkParsesToNull() {
        assertNull(TrunkWire.parseUpdate(Json.parseToJsonElement("""{"other":1}""")))
        assertNull(TrunkWire.parseUpdate(Json.parseToJsonElement("""{"recv":{"from":"~zod","sig":{"nope":{}}}}""")))
        assertNull(TrunkWire.parseUpdate(Json.parseToJsonElement("""[1,2]""")))
    }

    @Test
    fun ticketAndDeniedParse() {
        val t = TrunkWire.parseUpdate(
            Json.parseToJsonElement(
                """{"ticket":{"from":"~nec","name":"lounge","location":"http://h/group/talon/nec-lounge/","token":"abc"}}""",
            ),
        ) as TrunkUpdate.Ticket
        assertEquals("~nec", t.from)
        assertEquals(TrunkTicket("lounge", "http://h/group/talon/nec-lounge/", "abc"), t.ticket)

        val d = TrunkWire.parseUpdate(
            Json.parseToJsonElement(
                """{"denied":{"from":"~nec","name":"lounge","why":"not a member"}}""",
            ),
        ) as TrunkUpdate.Denied
        assertEquals("not a member", d.why)
    }

    @Test
    fun iceParsing() {
        val ice = TrunkWire.parseIce(
            Json.parseToJsonElement(
                """[{"url":"stun:h:3478","user":"","cred":""},{"url":"turn:h:3478","user":"u","cred":"p"}]""",
            ),
        )
        assertEquals(2, ice.size)
        assertEquals(IceServer("stun:h:3478", "", ""), ice[0])
        assertEquals(IceServer("turn:h:3478", "u", "p"), ice[1])
        assertEquals(emptyList(), TrunkWire.parseIce(Json.parseToJsonElement("{}")))
    }

    @Test
    fun fingerprintExtraction() {
        val sdp = "v=0\r\no=- 1 1 IN IP4 0.0.0.0\r\na=fingerprint:sha-256 AA:BB:CC\r\na=setup:actpass\r\n"
        assertEquals("sha-256 AA:BB:CC", sdpFingerprint(sdp))
        assertEquals("", sdpFingerprint("v=0\r\n"))
    }

    @Test
    fun policyParsing() {
        val pol = TrunkWire.parsePolicy(
            Json.parseToJsonElement(
                """{"mode":"allow","allow":["~zod","~nec"],"block":["~bus"]}""",
            ),
        )
        assertEquals(CallPolicy.Mode.Allow, pol.mode)
        assertEquals(setOf("~zod", "~nec"), pol.allow)
        assertEquals(setOf("~bus"), pol.block)

        // An unknown or missing mode must fail open rather than
        // silently locking the user out of their own calls.
        assertEquals(
            CallPolicy.Mode.Open,
            TrunkWire.parsePolicy(Json.parseToJsonElement("""{"mode":"weird"}""")).mode,
        )
        assertEquals(CallPolicy(), TrunkWire.parsePolicy(Json.parseToJsonElement("{}")))
    }

    @Test
    fun policyActionShapes() {
        assertEquals(
            """{"set-call-mode":"allow"}""",
            TrunkWire.setCallModeAction(CallPolicy.Mode.Allow).toString(),
        )
        assertEquals("""{"allow":"~zod"}""", TrunkWire.allowAction("~zod", true).toString())
        assertEquals("""{"unallow":"~zod"}""", TrunkWire.allowAction("~zod", false).toString())
        assertEquals("""{"block":"~bus"}""", TrunkWire.blockAction("~bus", true).toString())
        assertEquals("""{"unblock":"~bus"}""", TrunkWire.blockAction("~bus", false).toString())
    }

    @Test
    fun policyFactParsing() {
        val up = TrunkWire.parseUpdate(
            Json.parseToJsonElement(
                """{"policy":{"mode":"open","allow":[],"block":["~bus"]}}""",
            ),
        )
        assertEquals(CallPolicy(block = setOf("~bus")), (up as TrunkUpdate.Policy).policy)
    }

    @Test
    fun wireVersionParsing() {
        assertEquals(1, TrunkWire.parseWireVersion(Json.parseToJsonElement("""{"wire":1}""")))
        // A desk too old to answer, or one that answers with rubbish,
        // reads as 0 — which is a mismatch, not a silent pass.
        assertEquals(0, TrunkWire.parseWireVersion(Json.parseToJsonElement("{}")))
        assertEquals(
            0,
            TrunkWire.parseWireVersion(Json.parseToJsonElement("""{"wire":"x"}""")),
        )
    }
    // ---- set-ice ------------------------------------------------
    // The shape lib/trunk-json.hoon dejs's: all three fields are `so`
    // inside an `ot`, so every one must be present or gall refuses the
    // poke with a bad-key and the settings screen looks inert.

    @Test
    fun setIceActionShape() {
        val json = TrunkWire.setIceAction(
            listOf(
                IceServer("stun:sfu.example:3478", "", ""),
                IceServer("turn:sfu.example:3478", "talon", "s3cret"),
            ),
        ).jsonObject
        val servers = json["set-ice"]!!.jsonObject["servers"]!!.jsonArray
        assertEquals(2, servers.size)

        val stun = servers[0].jsonObject
        assertEquals(setOf("url", "user", "cred"), stun.keys)
        assertEquals("stun:sfu.example:3478", stun["url"]!!.jsonPrimitive.content)
        assertEquals("", stun["user"]!!.jsonPrimitive.content)

        val turn = servers[1].jsonObject
        assertEquals("turn:sfu.example:3478", turn["url"]!!.jsonPrimitive.content)
        assertEquals("talon", turn["user"]!!.jsonPrimitive.content)
        assertEquals("s3cret", turn["cred"]!!.jsonPrimitive.content)
    }

    @Test
    fun clearingIceSendsAnEmptyList() {
        val json = TrunkWire.setIceAction(emptyList()).jsonObject
        assertEquals(0, json["set-ice"]!!.jsonObject["servers"]!!.jsonArray.size)
    }

    @Test
    fun setIceRoundTripsThroughOurOwnParser() {
        // parseIce reads /x/ice, which the agent renders with the same
        // field names. If these two ever drift, adopt-then-rescry
        // silently reports the wrong server count.
        val servers = listOf(
            IceServer("stun:sfu.example:3478", "", ""),
            IceServer("turn:sfu.example:3478", "talon", "s3cret"),
        )
        val wire = TrunkWire.setIceAction(servers)
            .jsonObject["set-ice"]!!.jsonObject["servers"]!!
        assertEquals(servers, TrunkWire.parseIce(wire))
    }

    @Test
    fun iceSpecParsing() {
        // The exact shape TALON_DEFAULT_ICE ships in. The STUN entry's
        // trailing "||" is the case worth pinning: no user, no cred.
        val spec =
            "stun:sfu.example:3479||;turn:sfu.example:3479|talon|s3cret"
        assertEquals(
            listOf(
                IceServer("stun:sfu.example:3479", "", ""),
                IceServer("turn:sfu.example:3479", "talon", "s3cret"),
            ),
            TrunkWire.parseIceSpec(spec),
        )
    }

    @Test
    fun iceSpecToleratesJunk() {
        // A build with no default, and a spec with a stray separator,
        // must both come back empty or short rather than throwing —
        // this runs on every app start.
        assertEquals(emptyList(), TrunkWire.parseIceSpec(""))
        assertEquals(emptyList(), TrunkWire.parseIceSpec(";;"))
        assertEquals(
            listOf(IceServer("stun:a:1", "", "")),
            TrunkWire.parseIceSpec(";stun:a:1;"),
        )
    }

    @Test
    fun peekRoomActionShape() {
        // Mirrors lib/trunk-json.hoon's [%peek-room (ot ~[host name])].
        // Both keys are required; a missing one is a bad-key nack, which
        // before the channel read poke acks looked like nothing at all.
        val json = TrunkWire.peekRoomAction("~zod", "lounge").jsonObject
        val body = json["peek-room"]!!.jsonObject
        assertEquals(setOf("host", "name"), body.keys)
        assertEquals("~zod", body["host"]!!.jsonPrimitive.content)
        assertEquals("lounge", body["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun wireVersionMatchesTheAgent() {
        // The agent answers /x/version with ++wire-version. Bump both or
        // neither: a client claiming a version the desk doesn't speak
        // reports every ship as out of date.
        assertEquals(2, TrunkWire.WIRE_VERSION)
    }
}
