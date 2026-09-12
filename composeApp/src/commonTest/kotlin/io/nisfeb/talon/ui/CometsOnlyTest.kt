package io.nisfeb.talon.ui

import io.nisfeb.talon.data.ContactEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * One rule, checked at every surface that can show a word name: only a
 * comet gets one.
 *
 * Three places reach for a nym -- the display name, the contact sheet
 * and the mention picker -- and each is a separate call. This pins all
 * of them against every class of ship, so a fourth surface added later
 * cannot quietly start naming planets again.
 */
class CometsOnlyTest {

    private val comet = "~doznec-binwes-samper-siglet--fidpen-sogdur-wacser-wissun"

    private val notComets = listOf(
        "~zod",                                 // galaxy
        "~nec",                                 // galaxy
        "~marzod",                              // star
        "~sampel-palnet",                       // planet
        "~ricsul-bilwyt",                       // planet
        "~sampel-palnet-sampel-palnet",         // moon
    )

    @Test
    fun `only a comet has a nym at all`() {
        assertNotNull(Mnemonym.forShip(comet))
        assertNotNull(Mnemonym.display(comet))
        for (ship in notComets) {
            assertNull(Mnemonym.forShip(ship), "forShip($ship)")
            assertNull(Mnemonym.display(ship), "display($ship)")
        }
    }

    @Test
    fun `the display name falls through to the at-p for everything else`() {
        val map = ContactMap()
        assertEquals("..admire...attune", map.displayName(comet))
        for (ship in notComets) {
            assertEquals(ship, map.displayName(ship), "displayName($ship)")
        }
    }

    @Test
    fun `a nickname still wins for a comet`() {
        val map = ContactMap(
            contacts = listOf(ContactEntity(comet, "Sam", null, null)),
        )
        assertEquals("Sam", map.displayName(comet))
    }

    @Test
    fun `the mention picker matches words only for comets`() {
        val ships = notComets + comet
        val map = ContactMap()
        // The comet answers to its own first word...
        assertEquals(listOf(comet), suggestionsFor("admire", map, ships).map { it.ship })
        // ...and a planet answers to nothing but its @p.
        assertEquals(emptyList(), suggestionsFor("misrule", map, ships).map { it.ship })
        assertEquals(
            listOf("~ricsul-bilwyt"),
            suggestionsFor("ricsul", map, ships).map { it.ship },
        )
    }

    @Test
    fun `a comet is sixteen syllables and nothing else is`() {
        // The gate is the syllable count, so pin the boundary: one
        // syllable short or long is not a comet and gets no name.
        assertNull(Mnemonym.forShip("~doznec-binwes-samper-siglet--fidpen-sogdur-wacser"))
        assertNull(
            Mnemonym.forShip("~doznec-binwes-samper-siglet--fidpen-sogdur-wacser-wissun-doznec"),
        )
    }
}
