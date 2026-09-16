package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A full nym is the fingerprint plus its checksum, so it decodes back
 * to the comet it names. The abridged form cannot -- ten of its twelve
 * words are gone -- and must not pretend otherwise.
 */
class NymDecodeTest {

    private val comet = "~doznec-binwes-samper-siglet--fidpen-sogdur-wacser-wissun"
    private val other = "~racmus-mollen-fallyt-linpex--watres-sibbur-modlux-rinmex"

    @Test
    fun `a full nym names its comet`() {
        assertEquals(comet, Mnemonym.shipForNym(Mnemonym.forShip(comet)!!))
        assertEquals(other, Mnemonym.shipForNym(Mnemonym.forShip(other)!!))
    }

    @Test
    fun `the leading dots are optional to type`() {
        val nym = Mnemonym.forShip(comet)!!
        assertEquals(comet, Mnemonym.shipForNym(nym))
        assertEquals(comet, Mnemonym.shipForNym(nym.removePrefix("..")))
        assertEquals(comet, Mnemonym.shipForNym("  $nym  "))
    }

    @Test
    fun `an abridged name resolves to nothing`() {
        // It is two words of twelve. Anything else would be a guess.
        assertNull(Mnemonym.shipForNym("..admire...attune"))
        assertNull(Mnemonym.shipForNym("..admire"))
    }

    @Test
    fun `a bare word is not a name`() {
        // One word in sixteen passes the checksum; without the prefix
        // that is a typed nickname, not a comet.
        assertNull(Mnemonym.shipForNym("alone"))
        assertNull(Mnemonym.shipForNym("abducts"))
        // Two bare words are still not enough to be trusted.
        assertNull(Mnemonym.shipForNym("abducts.abate"))
    }

    @Test
    fun `a wrong word fails the checksum instead of naming someone else`() {
        val words = Mnemonym.forShip(comet)!!.removePrefix("..").split('.').toMutableList()
        words[3] = "yourselves"
        assertNull(Mnemonym.shipForNym(".." + words.joinToString(".")))
    }

    @Test
    fun `a word that is not in the list is not a name`() {
        assertNull(Mnemonym.shipForNym("..nonsense.words.here"))
        assertNull(Mnemonym.shipForNym("..zzzz"))
        assertNull(Mnemonym.shipForNym(""))
        assertNull(Mnemonym.shipForNym("~sampel-palnet"))
    }

    @Test
    fun `too many words is not a comet`() {
        val long = (1..13).joinToString(".") { "abate" }
        assertNull(Mnemonym.shipForNym("..$long"))
    }

    @Test
    fun `a short value with dropped leading zeroes still round-trips`() {
        // ..abducts is all sixteen bytes zero, which is a real @p.
        val nym = Mnemonym.encode(ByteArray(16), tweaked = false)
        assertEquals("..abducts", nym)
        assertEquals(Mnemonym.patpOf(ByteArray(16)), Mnemonym.shipForNym(nym))
    }
}
