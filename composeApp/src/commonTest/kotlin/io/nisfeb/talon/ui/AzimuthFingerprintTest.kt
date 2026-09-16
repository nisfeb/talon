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

    @Test
    fun `shax matches the dojo`() {
        // > `@ux`(shax 1)
        assertEquals(
            "0x9a4585773ce2ccd7a585c331d60a60d1e3b7d28cbb2ede3bc55445342f12f54b",
            show(AzimuthFingerprint.shax(atom("0x1"))),
        )
        // > `@ux`(shax 0xdead.beef)
        assertEquals(
            "0xf4bd98917e32f5adfc17121e00067a198eaebafbce109f930a10a85c3d4e0d9",
            show(AzimuthFingerprint.shax(atom("0xdead.beef"))),
        )
    }

    @Test
    fun `shas matches the dojo`() {
        // > `@ux`(shas %bfig 1)
        assertEquals(
            "0x6677898293baaef391b2d1fcbf50534ccc4141957106f324d5ade545bbc5dcd9",
            show(AzimuthFingerprint.shas(bfig, atom("0x1"))),
        )
    }

    @Test
    fun `shaf folds the halves like the dojo`() {
        // > `@ux`(shaf %bfig 1)
        assertEquals(
            "0xaa36c817e2bc5dd7441f34b904958f95",
            show(AzimuthFingerprint.shaf(bfig, atom("0x1"))),
        )
    }

    private val aut = atom("0x1111.2222.3333.4444.5555.6666.7777.8888.9999.aaaa.bbbb.cccc.dddd.eeee.ffff.0001")
    private val enc = atom("0x2222.3333.4444.5555.6666.7777.8888.9999.aaaa.bbbb.cccc.dddd.eeee.ffff.0001.0002")

    @Test
    fun `the pass is the suite byte then both keys`() {
        // > `@ux`(cat 3 %b (cat 8 aut enc))  -- 65 bytes, ending 0162
        val p = AzimuthFingerprint.pass(aut.reversedArray(), enc.reversedArray())
        assertEquals(65, p.size)
        assertEquals('b'.code.toByte(), p[0], "the suite byte is the lowest")
        assertEquals(true, show(p).endsWith("0162"))
    }

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
