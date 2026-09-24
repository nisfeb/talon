package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ship switcher lists the accounts you are signed in to, where
 * there is no such thing as somebody else's nickname to consult. It
 * still must not print a comet's @p.
 */
class ShipHandleTest {

    private val comet = "~doznec-binwes-samper-siglet--fidpen-sogdur-wacser-wissun"
    private val twinA = "~binfet-harmeb-tirmut-raclug--linnec-tobmed-hacdeg-dartul"
    private val twinB = "~binwed-watmeg-tadrel-modreg--nimfen-bidtus-finrus-dablep"

    @Test
    fun `a comet is its word name, with no contact data at all`() {
        assertEquals("..admire...attune", shipHandle(comet, nonCometNames = false))
        assertTrue(shipHandle(comet, nonCometNames = false) != comet)
    }

    @Test
    fun `every other ship is its at-p, which is the only name it has`() {
        for (ship in listOf("~zod", "~marzod", "~ricsul-bilwyt", "~sampel-palnet-sampel-palnet")) {
            assertEquals(ship, shipHandle(ship, nonCometNames = false))
        }
    }

    @Test
    fun `the long form tells two alike comets apart`() {
        // Both abridge to ..absolves...delay, so a switcher showing the
        // short name twice could not say which account was which.
        assertEquals(shipHandle(twinA, false), shipHandle(twinB, false))
        assertNotEquals(shipHandleLong(twinA), shipHandleLong(twinB))
        for (s in listOf(twinA, twinB)) {
            assertTrue(shipHandleLong(s)!!.startsWith(".."), "still a name, not an @p")
        }
    }

    @Test
    fun `a ship with no word name has no long form either`() {
        assertNull(shipHandleLong("~ricsul-bilwyt"))
        assertNull(shipHandleLong("~zod"))
    }

}
