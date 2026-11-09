package io.nisfeb.talon.armillary

import kotlin.test.Test
import kotlin.test.assertEquals

/** Why a device has no key, as the Armillary card says it: plain words, no HTTP codes. */
class KeyProblemLineTest {
    @Test
    fun `each failure is said plainly`() {
        assertEquals("Your Groundwire node refused: the vendor did not answer.", keyProblemLine(ArmillaryError.Refused(502, "the vendor did not answer")))
        assertEquals("Your Groundwire node refused: it gave no reason.", keyProblemLine(ArmillaryError.Refused(500, "")))
        assertEquals("Your Groundwire node did not answer. Try Refresh in a moment.", keyProblemLine(ArmillaryError.Unreachable(Exception("reset"))))
        assertEquals("Your Groundwire node's answer could not be read.", keyProblemLine(ArmillaryError.Garbled(Exception("bad json"))))
        // ensureKey's own words pass through as they are.
        assertEquals("Your Groundwire node has asked the vendor for a key and is still waiting. Try Refresh in a moment.",
            keyProblemLine(IllegalStateException("Your Groundwire node has asked the vendor for a key and is still waiting. Try Refresh in a moment.")))
        assertEquals("Your Groundwire node did not answer. Try Refresh in a moment.", keyProblemLine(RuntimeException()))
    }
}
