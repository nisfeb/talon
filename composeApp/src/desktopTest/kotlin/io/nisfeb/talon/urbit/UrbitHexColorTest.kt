package io.nisfeb.talon.urbit

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The tint a profile edit sends is parsed on the ship by
 * `(slav %ux (cat 3 '0x' s))`, and slav wants the canonical @ux: four
 * digit groups counted from the right, no leading zero on the front
 * group. Every expectation below was checked against slav in a dojo,
 * including the two that used to be produced and rejected.
 */
class UrbitHexColorTest {

    private fun tint(hex: String) = TlonChatRepo.urbitHexColorForTest(hex)

    @Test
    fun `a bright colour groups two and four`() {
        // > `@ux`(slav %ux '0xff.5050')  ->  0xff.5050
        assertEquals("ff.5050", tint("#FF5050"))
        assertEquals("ff.5050", tint("ff5050"))
    }

    @Test
    fun `a dark red channel drops its leading zero`() {
        // This is the one that crashed the poke: "0a.1b2c" is not a
        // canonical @ux and slav refuses it outright.
        assertEquals("a.1b2c", tint("#0a1b2c"))
        assertEquals("a.1b2c", tint("#0A1B2C"))
    }

    @Test
    fun `a colour with no red at all loses the whole group`() {
        assertEquals("ffff", tint("#00ffff"))
        assertEquals("ff", tint("#0000ff"))
        assertEquals("1.0000", tint("#010000"))
    }

    @Test
    fun `black is zero, not six zeroes`() {
        assertEquals("0", tint("#000000"))
        assertEquals("0", tint(""))
    }

    @Test
    fun `every colour in the wheel produces a canonical value`() {
        // Canonical means: no dot-group longer than four, no leading
        // zero on the front group, and nothing empty.
        for (v in 0..0xFFFFFF step 0x111) {
            val out = tint("#" + v.toString(16).padStart(6, '0'))
            val groups = out.split('.')
            assertEquals(true, groups.all { it.isNotEmpty() && it.length <= 4 }, out)
            assertEquals(true, groups.drop(1).all { it.length == 4 }, out)
            if (out != "0") assertEquals(false, out.startsWith("0"), out)
        }
    }
}
