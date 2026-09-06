package io.nisfeb.talon.call

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The callee has to be able to send video too.
 *
 * Answering used to leave our camera on a second, unassociated
 * transceiver — the constructor's addTransceiver only associates on the
 * offering side — so the answer advertised `recvonly` for video and
 * video only ever flowed caller-to-callee. This asserts on the SDP,
 * which needs no connectivity, unlike the opt-in loopback test.
 */
class AnswerVideoDirectionTest {

    /** The media direction of the SDP's video m-section. */
    private fun videoDirection(sdp: String): String? {
        var inVideo = false
        for (line in sdp.lines()) {
            if (line.startsWith("m=")) inVideo = line.startsWith("m=video")
            if (!inVideo) continue
            val t = line.trim()
            if (t == "a=sendrecv" || t == "a=sendonly" ||
                t == "a=recvonly" || t == "a=inactive"
            ) {
                return t.removePrefix("a=")
            }
        }
        return null
    }

    @Test
    fun theAnswerOffersToSendVideoNotJustReceiveIt() = runBlocking {
        val caller = DesktopCallEngine()
        val callee = DesktopCallEngine()
        try {
            val offer = caller.createOffer()
            assertTrue(
                offer.sdp.contains("m=video"),
                "the offer should negotiate video up front (no renegotiation later)",
            )

            val answer = callee.acceptOffer(offer)
            val dir = videoDirection(answer.sdp)
            assertTrue(
                dir == "sendrecv" || dir == "sendonly",
                "the callee must be able to send video, but its answer said '$dir'",
            )
        } finally {
            caller.close()
            callee.close()
        }
    }
}
