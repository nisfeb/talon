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
