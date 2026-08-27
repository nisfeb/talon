package io.nisfeb.talon.call

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Wire shapes must match urbit/trunk/lib/trunk-json.hoon verbatim. */
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
}
