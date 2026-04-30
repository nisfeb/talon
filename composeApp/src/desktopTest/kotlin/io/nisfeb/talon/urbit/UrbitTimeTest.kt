package io.nisfeb.talon.urbit

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrbitTimeTest {

    @Test
    fun `da at unix epoch equals DA_UNIX_EPOCH`() {
        val da = UrbitTime.unixMsToDa(0L)
        assertEquals(
            BigInteger("170141184475152167957503069145530368000"),
            da,
        )
    }

    @Test
    fun `unixMsToDa grows monotonically with time`() {
        val a = UrbitTime.unixMsToDa(1_000_000L)
        val b = UrbitTime.unixMsToDa(2_000_000L)
        assertTrue("later ms → larger da", b > a)
    }

    @Test
    fun `daToUd groups digits in threes from right`() {
        assertEquals("1.234", UrbitTime.daToUd(BigInteger.valueOf(1234)))
        assertEquals("12.345", UrbitTime.daToUd(BigInteger.valueOf(12345)))
        assertEquals("123.456", UrbitTime.daToUd(BigInteger.valueOf(123456)))
        assertEquals("1.234.567", UrbitTime.daToUd(BigInteger.valueOf(1234567)))
    }

    @Test
    fun `daToUd small numbers unchanged`() {
        assertEquals("0", UrbitTime.daToUd(BigInteger.ZERO))
        assertEquals("42", UrbitTime.daToUd(BigInteger.valueOf(42)))
        assertEquals("999", UrbitTime.daToUd(BigInteger.valueOf(999)))
    }

    @Test
    fun `formatPostId shape is author slash dotted-da`() {
        val id = UrbitTime.formatPostId("~sampel-palnet", BigInteger.valueOf(1234567))
        assertEquals("~sampel-palnet/1.234.567", id)
    }

    @Test
    fun `real-world timestamp produces 39-digit da`() {
        // 2026-04-24T…Z-ish. We just check the digit count is stable.
        val da = UrbitTime.unixMsToDa(1777055041699L)
        // Expect around 39 digits in the decimal form.
        assertTrue(da.toString().length in 38..40)
    }

    @Test
    fun `dotAtom of daToUd undotted matches daToUd`() {
        // Both the hand-rolled dotAtom in UrbitIds and daToUd should
        // produce identical dot-grouping for the same numeric value.
        val ms = 1_700_000_000_000L
        val da = UrbitTime.unixMsToDa(ms)
        val udViaDa = UrbitTime.daToUd(da)
        val udViaDot = dotAtom(da.toString())
        assertEquals(udViaDa, udViaDot)
    }
}
