package io.nisfeb.talon.call

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

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
        val c = CallController(h.session, CallEngineProvider { HangingCallEngine() })
        val recording = MutableStateFlow(true)
        try {
            c.start()
            h.awaitConnected()
            c.beginRecordingAnnounce("~zod", "lounge", recording)
            h.await { sent(h, "start-recording") > 0 }
            assertTrue(sent(h, "stop-recording") == 0)
            recording.value = false
            h.await { sent(h, "stop-recording") == 1 }
        } finally {
            c.stop()
        }
    }
}
