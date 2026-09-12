package io.nisfeb.talon.ui

import io.nisfeb.talon.data.ContactEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Completing a mention has to land on the ship somebody meant, and a
 * comet's shown name is not enough to identify one.
 *
 * The abridgement keeps the first and last word of twelve, so two
 * comets collide whenever both ends agree -- a birthday collision over
 * 2048 squared, which a search finds after about three thousand
 * comets, not three million. The pair below is real: both show as
 * `..absolves...delay`. So the row carries the exact @p, and what gets
 * inserted is the @p, never the name.
 */
class MentionResolvesShipTest {

    private val twinA = "~binfet-harmeb-tirmut-raclug--linnec-tobmed-hacdeg-dartul"
    private val twinB = "~binwed-watmeg-tadrel-modreg--nimfen-bidtus-finrus-dablep"
    private val comet = "~doznec-binwes-samper-siglet--fidpen-sogdur-wacser-wissun"

    private fun hits(q: String, ships: List<String>) =
        suggestionsFor(q, ContactMap(), ships)

    @Test
    fun `two comets really can show the same name`() {
        assertEquals("..absolves...delay", Mnemonym.display(twinA))
        assertEquals(Mnemonym.display(twinA), Mnemonym.display(twinB))
        // The full nyms differ, which is what the picker matches on.
        assertNotEquals(Mnemonym.forShip(twinA), Mnemonym.forShip(twinB))
    }

    @Test
    fun `an ambiguous name offers both, each with its own ship`() {
        val out = hits("absolves", listOf(twinA, twinB))
        assertEquals(2, out.size, "both have to be offered; picking is the user's")
        assertEquals(setOf(twinA, twinB), out.map { it.ship }.toSet())
    }

    @Test
    fun `the full nym separates them`() {
        val onlyA = Mnemonym.forShip(twinA)!!.trimStart('.').split('.').take(2).joinToString(".")
        val out = hits(onlyA, listOf(twinA, twinB))
        assertEquals(listOf(twinA), out.map { it.ship })
    }

    @Test
    fun `the name on screen is a name you can type`() {
        // This is the whole point: what the app shows is the abridged
        // form, so that string and its last word have to find the ship.
        // Before, only a prefix of the full nym did, and the one name
        // anybody could actually see matched nothing.
        assertEquals(listOf(comet), hits("..admire...attune", listOf(comet)).map { it.ship })
        assertEquals(listOf(comet), hits("admire...attune", listOf(comet)).map { it.ship })
        assertEquals(listOf(comet), hits("attune", listOf(comet)).map { it.ship })
        assertEquals(listOf(comet), hits("admire", listOf(comet)).map { it.ship })
        assertEquals(listOf(comet), hits("admire.ev", listOf(comet)).map { it.ship })
    }

    @Test
    fun `a word from the middle finds it too`() {
        // Where two comets differ is the middle, so it has to be
        // reachable -- the ends are exactly what they may share.
        assertEquals(listOf(comet), hits("dethroned", listOf(comet)).map { it.ship })
    }

    @Test
    fun `the row shows the exact ship, which is what gets inserted`() {
        val s = hits("absolves", listOf(twinA, twinB)).first { it.ship == twinA }
        // Suggestion.ship is what ChatComposer inserts verbatim.
        assertEquals(twinA, s.ship)
        assertTrue(s.ship.startsWith("~"), "a name is never what gets sent")
    }

    @Test
    fun `a nickname still resolves to its own ship`() {
        val map = ContactMap(contacts = listOf(ContactEntity(twinB, "Bee", null, null)))
        val out = suggestionsFor("bee", map, listOf(twinA, twinB))
        assertEquals(listOf(twinB), out.map { it.ship })
    }
}
