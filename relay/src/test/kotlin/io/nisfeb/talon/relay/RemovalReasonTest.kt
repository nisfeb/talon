package io.nisfeb.talon.relay

import kotlin.test.Test
import kotlin.test.assertEquals

class RemovalReasonTest {
    @Test
    fun `a removal says why, from the app's own words only`() {
        assertEquals("the app moved it to its ship's own push (%trunk), which pushes to it now", removalReason("ship-push"))
        assertEquals("the owner turned the relay off for this ship", removalReason("off"))
        assertEquals("the ship was forgotten on that device", removalReason("forgotten"))
        assertEquals("the app asked, giving no reason (an older Talon)", removalReason(null))
        assertEquals("the app asked, giving no reason (an older Talon)", removalReason("<script>"), "nothing the app sends is logged as it came")
    }
}
