package io.nisfeb.talon.orrery

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The menu's Orrery entry. "Orrery does not appear in the hamburger menu
 * despite being on ... it appeared after a load": it waited for the ship
 * to answer the first look.
 */
class OrreryOfferedTest {
    @Test
    fun `on, it is offered while the ship is still being asked, and gone only when it is missing`() {
        assertEquals(
            mapOf(
                OrreryAvailability.UNKNOWN to true,
                OrreryAvailability.PRESENT to true,
                OrreryAvailability.MISSING to false,
                OrreryAvailability.SIGNED_OUT to false,
            ),
            OrreryAvailability.entries.associateWith { orreryOffered(switchedOn = true, availability = it) },
        )
    }

    @Test
    fun `off, it is never offered`() {
        assertEquals(emptyList(), OrreryAvailability.entries.filter { orreryOffered(switchedOn = false, availability = it) })
    }
}
