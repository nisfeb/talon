package io.nisfeb.talon.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A comet's first-login landing joined whichever ship was open when it
 * ran: switching ships mid-landing poked the other one to join the group
 * and put the calling desk on it. It belongs to the ship it was made for.
 */
class LandingForTest {
    @Test
    fun `the landing runs only on the ship it was made for`() {
        assertEquals("~host/group", landingFor("~comet" to "~host/group", "~comet"))
        assertNull(landingFor("~comet" to "~host/group", "~zod"))
        assertNull(landingFor("~comet" to "~host/group", null))
        assertNull(landingFor(null, "~comet"))
    }
}
