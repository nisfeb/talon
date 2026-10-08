package io.nisfeb.talon.armillary

import kotlin.test.Test
import kotlin.test.assertEquals

/** Why a device has no key, as the Armillary card says it: plain words, no HTTP codes. */
class KeyProblemLineTest {
    @Test
    fun `each failure is said plainly`() {
        assertEquals("Your ship refused: the vendor did not answer.", keyProblemLine(ArmillaryError.Refused(502, "the vendor did not answer")))
        assertEquals("Your ship refused: it gave no reason.", keyProblemLine(ArmillaryError.Refused(500, "")))
        assertEquals("Your ship did not answer. Try Refresh in a moment.", keyProblemLine(ArmillaryError.Unreachable(Exception("reset"))))
        assertEquals("Your ship's answer could not be read.", keyProblemLine(ArmillaryError.Garbled(Exception("bad json"))))
        // ensureKey's own words pass through as they are.
        assertEquals("Your ship has asked the vendor for a key and is still waiting. Try Refresh in a moment.",
            keyProblemLine(IllegalStateException("Your ship has asked the vendor for a key and is still waiting. Try Refresh in a moment.")))
        assertEquals("Your ship did not answer. Try Refresh in a moment.", keyProblemLine(RuntimeException()))
    }
}
