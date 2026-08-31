package io.nisfeb.talon.call

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins the shared mic-processing profile both the 1:1 engine and the
 * party-line link source from. All four flags off is exactly what a
 * bare AudioOptions gives — and what shipped for months as the "poor
 * audio quality" field report — so a drift back is silent until a user
 * complains. Skips where the native library can't load, same as
 * DesktopAudioDevicesTest.
 */
class MicAudioOptionsTest {

    @Test
    fun allFourProcessingFlagsAreOn() {
        val opts = try {
            micAudioOptions()
        } catch (e: UnsatisfiedLinkError) {
            println("native webrtc unavailable here — skipping: ${e.message}")
            return
        } catch (e: NoClassDefFoundError) {
            println("native webrtc unavailable here — skipping: ${e.message}")
            return
        }

        assertTrue(opts.echoCancellation, "echo cancellation must be on")
        assertTrue(opts.autoGainControl, "auto gain control must be on")
        assertTrue(opts.noiseSuppression, "noise suppression must be on")
        assertTrue(opts.highpassFilter, "highpass filter must be on")
    }
}
