package io.nisfeb.talon.notify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SystemNotifierTest {
    @Test fun `the id is read out of gdbus's answer`() {
        assertEquals(42L, gdbusId("(uint32 42,)\n"))
        assertNull(gdbusId("Error: GDBus.Error:org.freedesktop.DBus.Error.ServiceUnknown"))
    }
}
