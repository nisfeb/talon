package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The official 128-bit vectors from gwbtc/mnemonyms test-vectors.json,
 * verbatim. 128 bits is exactly a comet, which is the only thing Talon
 * names, and these pin both the word list and the untweaked `..` form
 * against the reference rather than against our own past output.
 */
class MnemonymVectorsTest {

    private val vectors: List<Pair<String, String>> = listOf(
        "00000000000000000000000000000000" to
            "..abducts",
        "7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f" to
            "..escapes.upsets.retook.withdrew.untamed.protest.verbose.unfit.escapes.upsets.retook.withheld",
        "80808080808080808080808080808080" to
            "..event.accede.auteurs.ablaze.admire.confers.abridge.allege.event.accede.auteurs.abet",
        "ffffffffffffffffffffffffffffffff" to
            "..yourselves.yourselves.yourselves.yourselves.yourselves.yourselves.yourselves.yourselves.yourselves.yourselves.yourselves.withdraw",
        "9e885d952ad362caeb4efe34a8e91bd2" to
            "..machine.congeal.discount.delays.cassette.discounts.ordeal.retook.caprice.coquette.convicts.mistake",
        "c0ba5a8e914111210f2bd131f3d5e08d" to
            "..pulsate.remade.misspells.award.aloft.harpoons.concede.ensoul.brunettes.machines.enraged.askew",
        "23db8160a31d3e0dca3688ed941adbf3" to
            "..baguette.resort.depart.conversed.remold.address.beguile.relaxed.unclaimed.massage.platoon.taboo",
        "f30f8c1da665478f49b001d94c5fc452" to
            "..unlocks.entrance.adjoin.decant.defunct.record.befalls.abate.reproach.digress.unhurt.mistook",
    )

    private fun hexBytes(hex: String) =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun `every official 128-bit vector round-trips`() {
        for ((hex, expected) in vectors) {
            assertEquals(expected, Mnemonym.encode(hexBytes(hex), tweaked = false), hex)
        }
    }

    @Test
    fun `leading zero words are dropped`() {
        // All zeros collapses to the single word the checksum leaves.
        assertEquals("..abducts", Mnemonym.encode(ByteArray(16), tweaked = false))
    }

    @Test
    fun `the word list is the upstream one`() {
        assertEquals(2048, MNEMONYM_WORDS.size)
        assertEquals(2048, MNEMONYM_WORDS.toSet().size, "upstream de-duplicated the list")
        assertEquals("aback", MNEMONYM_WORDS.first())
        assertEquals("yourselves", MNEMONYM_WORDS.last())
    }
}
