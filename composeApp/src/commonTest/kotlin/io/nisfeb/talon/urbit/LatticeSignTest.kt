package io.nisfeb.talon.urbit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The block people paste around. The two numbers are the whole point:
 * a digest and a signature are atoms far past any integer type, so they
 * are carried as their digits and must survive the round trip exactly.
 */
class LatticeSignTest {
    private val digest = "7".repeat(78)
    private val sig = "9".repeat(154)
    private val rec = SignedRecord(
        ship = "~sampel-palnet", life = "3", alg = "ed25519", salt = "lattice", digest = digest, sig = sig,
    )

    @Test
    fun `a record survives being written out and read back`() {
        assertEquals(rec, signedRecordIn(rec.armor()))
    }

    @Test
    fun `a record is found in the middle of a pasted message`() {
        val pasted = "here you go:\n\n${rec.armor()}\n\nthat's the file I mentioned"
        assertEquals(rec, signedRecordIn(pasted))
    }

    @Test
    fun `text with no block, or a broken one, reads as nothing`() {
        assertNull(signedRecordIn("no signature here"))
        assertNull(signedRecordIn(rec.armor().replace(sig, "not-a-number")))
        assertNull(signedRecordIn(rec.armor().substringBefore("-----END")))
    }
}
