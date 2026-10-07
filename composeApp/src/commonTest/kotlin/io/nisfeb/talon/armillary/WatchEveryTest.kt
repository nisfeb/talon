package io.nisfeb.talon.armillary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchEveryTest {
    @Test
    fun `a payment is watched closely for two minutes, a bitcoin one loosely after`() {
        assertEquals(ArmillaryRepo.WATCH_EVERY_MS, ArmillaryRepo.watchEvery(0))
        assertEquals(ArmillaryRepo.WATCH_EVERY_MS, ArmillaryRepo.watchEvery(ArmillaryRepo.WATCH_MS - 1))
        assertEquals(ArmillaryRepo.BTC_WATCH_LATER_EVERY_MS, ArmillaryRepo.watchEvery(ArmillaryRepo.WATCH_MS))
        var reads = 0
        var waited = 0L
        while (waited < ArmillaryRepo.BTC_WATCH_MS) { waited += ArmillaryRepo.watchEvery(waited); reads++ }
        assertTrue(reads <= 50, "a 15-minute bitcoin watch reads $reads times; at 5 s it was 180")
    }
}
