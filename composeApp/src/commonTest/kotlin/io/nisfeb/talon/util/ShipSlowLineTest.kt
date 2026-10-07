package io.nisfeb.talon.util

import io.nisfeb.talon.urbit.ShipHealth
import kotlin.test.Test
import kotlin.test.assertEquals

/** The calm line while writes wait, in the words vere's healthz gives. */
class ShipSlowLineTest {
    @Test
    fun `the line says busy, down or out of reach, and slow otherwise`() {
        assertEquals("Your ship is busy. 2 queued for when it catches up.", shipSlowLine(2, ShipHealth.BUSY))
        assertEquals("Your ship is down. 1 queued for when it's back.", shipSlowLine(1, ShipHealth.DOWN))
        assertEquals("Can't reach your ship. 3 queued for when it's back.", shipSlowLine(3, ShipHealth.UNREACHABLE))
        for (h in listOf(ShipHealth.IDLE, ShipHealth.UNKNOWN, null)) {
            assertEquals("Your ship is slow. 1 queued for when it's back.", shipSlowLine(1, h), "$h")
        }
    }
}
