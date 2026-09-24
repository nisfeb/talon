package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Vectors taken from a running ship's dojo, not from reading the
 * source. Every line here was printed by ~nec before it was written
 * down, because the whole of this file is byte order and a wrong
 * answer is indistinguishable from a right one by inspection.
 */
class AzimuthFingerprintTest {

    /** An @ux as Urbit prints it -> the atom's little-endian bytes. */
    private fun atom(hex: String): ByteArray {
        val h = hex.removePrefix("0x").replace(".", "").let {
            if (it.length % 2 == 1) "0$it" else it
        }
        val be = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return be.reversedArray()
    }

    private fun show(b: ByteArray): String =
        "0x" + b.reversedArray().joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }.trimStart('0')

    private val bfig = byteArrayOf(0x62, 0x66, 0x69, 0x67)  // %bfig, LSB first

    private val aut = atom("0x1111.2222.3333.4444.5555.6666.7777.8888.9999.aaaa.bbbb.cccc.dddd.eeee.ffff.0001")
    private val enc = atom("0x2222.3333.4444.5555.6666.7777.8888.9999.aaaa.bbbb.cccc.dddd.eeee.ffff.0001.0002")

    @Test
    fun `the fingerprint matches the dojo end to end`() {
        // > `@ux`(shaf %bfig (cat 3 %b (cat 8 aut enc)))
        val fig = AzimuthFingerprint.of(aut.reversedArray(), enc.reversedArray(), suite = 1)!!
        assertEquals(16, fig.size, "a fingerprint is 128 bits, which is comet-sized")
        assertEquals("0xdcd10005f985996f3da127ebf1822033", show(fig))
    }

    @Test
    fun `a ship with no usable keys has no fingerprint`() {
        val z = ByteArray(32)
        assertNull(AzimuthFingerprint.of(z, z, suite = 1), "unset keys are not an identity")
        assertNull(AzimuthFingerprint.of(aut.reversedArray(), enc.reversedArray(), suite = 2))
        assertNull(AzimuthFingerprint.of(ByteArray(31), ByteArray(32), suite = 1))
    }
}
