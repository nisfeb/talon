package io.nisfeb.talon.urbit

import com.ionspin.kotlin.bignum.integer.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class UrbitTimeTest {

    @Test
    fun `da at unix epoch equals DA_UNIX_EPOCH`() {
        val da = UrbitTime.unixMsToDa(0L)
        assertEquals(
            BigInteger.parseString("170141184475152167957503069145530368000"),
            da,
        )
    }

    @Test
    fun `daToUd groups digits in threes from right`() {
        assertEquals("1.234", UrbitTime.daToUd(BigInteger.fromInt(1234)))
        assertEquals("12.345", UrbitTime.daToUd(BigInteger.fromInt(12345)))
        assertEquals("123.456", UrbitTime.daToUd(BigInteger.fromInt(123456)))
        assertEquals("1.234.567", UrbitTime.daToUd(BigInteger.fromInt(1234567)))
    }

    @Test
    fun `formatPostId shape is author slash dotted-da`() {
        val id = UrbitTime.formatPostId("~sampel-palnet", BigInteger.fromInt(1234567))
        assertEquals("~sampel-palnet/1.234.567", id)
    }

    @Test
    fun `daToUnixMs is the inverse of unixMsToDa`() {
        // Round-trip: a real-world timestamp should survive the
        // forward-then-back conversion exactly.
        val ms = 1_777_055_041_699L
        val da = UrbitTime.unixMsToDa(ms)
        assertEquals(ms, UrbitTime.daToUnixMs(da))
    }

    @Test
    fun `daToUnixMs returns null for da before unix epoch`() {
        val tooEarly = BigInteger.fromInt(1234)
        assertEquals(null, UrbitTime.daToUnixMs(tooEarly))
    }

}
