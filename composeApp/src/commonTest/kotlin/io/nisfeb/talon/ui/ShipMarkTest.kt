package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShipMarkTest {
    @Test
    fun `a ship keeps its colour, and ships spread across the colours`() {
        assertEquals(shipMarkColor("~sampel-palnet"), shipMarkColor("~sampel-palnet"))
        val ships = listOf("~zod", "~nec", "~bud", "~wes", "~sev", "~per", "~sut", "~let", "~ful", "~pen", "~syt", "~dur",
            "~sampel-palnet", "~dopzod-bitnux", "~lomder-librun", "~fabnev-hinmur")
        val used = ships.map(::shipMarkColor).toSet()
        assertTrue(used.size >= 6, "${used.size} colours for ${ships.size} ships")
        assertTrue(used.all { it in SHIP_MARK_COLORS })
    }
}
