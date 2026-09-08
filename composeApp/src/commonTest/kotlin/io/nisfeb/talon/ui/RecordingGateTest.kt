package io.nisfeb.talon.ui

import io.nisfeb.talon.call.TrunkWire
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingGateTest {
    private val wire = TrunkWire.WIRE_VERSION_RECORDING

    @Test
    fun onlyAdminsMayRecord() {
        assertTrue(recordingAllowed(true, true, true, "~zod", wire, isAdmin = true))
        assertFalse(recordingAllowed(true, true, true, "~zod", wire, isAdmin = false))
        assertFalse(recordingAllowed(true, true, true, "~zod", wire - 1, isAdmin = true))
        assertFalse(recordingAllowed(false, true, true, "~zod", wire, isAdmin = true))
    }
}
