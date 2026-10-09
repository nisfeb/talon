package io.nisfeb.talon.call

import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * One microphone source per processing setting, shared by every call:
 * webrtc-java 0.17 cannot free a source, so one per call leaked one per
 * call and per party-line republish (2026-10-09).
 */
class MicSourceTest {
    @Test
    fun `calls share the microphone's source, and a changed setting gets its own`() {
        val was = MicProcessingSettings.current
        try {
            val first = DesktopWebRtcFactory.micSource()
            assertSame(first, DesktopWebRtcFactory.micSource(), "the next call takes the same source")
            MicProcessingSettings.current = was.copy(noiseSuppression = !was.noiseSuppression)
            val other = DesktopWebRtcFactory.micSource()
            if (DesktopWebRtcFactory.audioProcessing) assertNotSame(first, other, "a source with the old setting is not reused for the new one")
            MicProcessingSettings.current = was
            assertSame(first, DesktopWebRtcFactory.micSource(), "and the first comes back with its setting")
        } finally {
            MicProcessingSettings.current = was
        }
    }
}
