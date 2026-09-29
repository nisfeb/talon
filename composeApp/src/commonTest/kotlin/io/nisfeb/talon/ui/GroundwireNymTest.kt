package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A Groundwire comet's name is the scheme's tweaked form: the same words
 * with one dot, where a comet nobody has attested has two. Both of these,
 * a signed-in Groundwire comet and one it looked at, read `..` until now.
 */
class GroundwireNymTest {
    private val me = "..renewed.erupt.prepare.ablate.outdid.demote.disburse.ensures.perfects.imbue.defames.involve"
    private val them = "..bespoke.unnerved.describe.convince.inhale.charade.relieve.obey.reword.dislodge.hereby.kazoo"

    @Test
    fun `a Groundwire comet is named with one dot, the same words, full and short`() {
        val ship = assertNotNull(Mnemonym.shipForNym(me), "the name decodes to its comet")
        assertEquals(me, Mnemonym.forShip(ship), "not known yet: untweaked")
        assertEquals("..renewed...involve", Mnemonym.display(ship))
        val before = AzimuthNames.generation.value
        Mnemonym.markGroundwire(ship)
        assertEquals(me.removePrefix("."), Mnemonym.forShip(ship))
        assertEquals(".renewed...involve", Mnemonym.display(ship))
        assertTrue(AzimuthNames.generation.value > before, "every name on screen is drawn again")
        assertEquals(ship, Mnemonym.shipForNym(".renewed.erupt.prepare.ablate.outdid.demote.disburse.ensures.perfects.imbue.defames.involve"), "and the single-dot name reads back")
    }

    @Test
    fun `learning it again changes nothing, and asks the name to redraw only once`() {
        val ship = assertNotNull(Mnemonym.shipForNym(them))
        Mnemonym.markGroundwire(ship)
        val after = AzimuthNames.generation.value
        Mnemonym.markGroundwire(ship)
        assertEquals(after, AzimuthNames.generation.value)
        assertEquals(".bespoke...kazoo", Mnemonym.display(ship))
    }
}
