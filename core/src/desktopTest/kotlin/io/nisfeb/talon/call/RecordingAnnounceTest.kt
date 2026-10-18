package io.nisfeb.talon.call

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The recording badge a host shows goes with the recording (review
 * A1-8, 2026-10-17). It ran on until the result dialog closed, so the
 * host showed us recording a line we had left, and with that chat off
 * screen it never stopped.
 */
class RecordingAnnounceTest {
    private fun sent(h: TrunkHarness, kind: String) =
        h.putsSnapshot().count { it.contains("\"json\":{\"$kind\":{\"host\":\"~zod\",\"name\":\"lounge\"}}") }

    @Test
    fun `the badge goes when the recording ends`() = runBlocking<Unit> {
        val h = TrunkHarness()
        val c = CallController(h.session, CallEngineProvider { HangingCallEngine() }, recordHeartbeatMs = 100)
        val recording = MutableStateFlow(true)
        try {
            c.start()
            h.awaitConnected()
            c.beginRecordingAnnounce("~zod", "lounge", recording)
            h.await { sent(h, "start-recording") >= 3 }
            assertEquals(0, sent(h, "stop-recording"), "said stopped while recording")
            recording.value = false
            h.await { sent(h, "stop-recording") == 1 }
            // Quiet after: a heartbeat left going put the badge back.
            val starts = sent(h, "start-recording")
            delay(500)
            assertEquals(starts, sent(h, "start-recording"))
        } finally {
            c.stop()
        }
    }
}
