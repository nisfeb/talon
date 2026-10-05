package io.nisfeb.talon.comet

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Names as %trunk's lib/mnemonym.hoon mints them into a ticket's sub.
 * The same three comets are pinned in gen/test-mnemonym.hoon there, so
 * a drift on either side fails a test.
 */
class GaleneNameTest {

    @Test
    fun `a Groundwire comet's name decodes to its ship`() {
        assertEquals(
            "~foppel-fitdyn-doznux-fithut--somdur-famdev-forpet-daplyd",
            shipOfGaleneName(".renewed.erupt.prepare.ablate.outdid.demote.disburse.ensures.perfects.imbue.defames.involve"),
        )
    }

    @Test
    fun `any other comet's name decodes the same way`() {
        assertEquals(
            "~larwyx-monder-winpel-timwyd--timben-botfun-harpub-daplyd",
            shipOfGaleneName("..retrieves.unmasked.giraffes.divides.unseen.assess.parlays.forewarn.forbid.caressed.hereby.invests"),
        )
    }

    @Test
    fun `dropped leading words are zeroes`() {
        // An upstream vector: 128 zero bits are the one word "abducts".
        assertEquals(cometPatp(ByteArray(16)), shipOfGaleneName("..abducts"))
    }

    @Test
    fun `anything that is not a nym comes back as it is`() {
        for (name in listOf("~ricsul-bilwyt", "~zod", "listener", "", ".", "..notaword.at.all", ".a" + ".abet".repeat(12))) {
            assertEquals(name, shipOfGaleneName(name))
        }
    }
}
