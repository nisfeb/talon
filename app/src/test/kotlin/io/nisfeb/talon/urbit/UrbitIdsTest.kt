package io.nisfeb.talon.urbit

import org.junit.Assert.assertEquals
import org.junit.Test

class UrbitIdsTest {

    // ─── dotAtom ─────────────────────────────────────────────────

    @Test
    fun `length leq 3 unchanged`() {
        assertEquals("1", dotAtom("1"))
        assertEquals("99", dotAtom("99"))
        assertEquals("999", dotAtom("999"))
    }

    @Test
    fun `four digits get one dot`() {
        assertEquals("1.000", dotAtom("1000"))
        assertEquals("1.234", dotAtom("1234"))
    }

    @Test
    fun `larger numbers group every 3 from the right`() {
        assertEquals("12.345", dotAtom("12345"))
        assertEquals("123.456", dotAtom("123456"))
        assertEquals("1.234.567", dotAtom("1234567"))
    }

    @Test
    fun `real post id dot-grouped`() {
        // Actual id from a prod log — 39 digits.
        val raw = "170141184507932790143209384169177088000"
        val expected =
            "170.141.184.507.932.790.143.209.384.169.177.088.000"
        assertEquals(expected, dotAtom(raw))
    }

    @Test
    fun `already-dotted strings pass through`() {
        assertEquals("1.234", dotAtom("1.234"))
        assertEquals("170.141.184", dotAtom("170.141.184"))
    }

    @Test
    fun `non-numeric pass through`() {
        // Author-prefixed ids shouldn't be dotted.
        assertEquals("~sampel-palnet/12345", dotAtom("~sampel-palnet/12345"))
        assertEquals("abc", dotAtom("abc"))
    }

    @Test
    fun `empty string unchanged`() {
        assertEquals("", dotAtom(""))
    }

    // ─── undotAtom ──────────────────────────────────────────────

    @Test
    fun `undotAtom strips dots`() {
        assertEquals("1234", undotAtom("1.234"))
        assertEquals(
            "170141184507932790143209384169177088000",
            undotAtom("170.141.184.507.932.790.143.209.384.169.177.088.000"),
        )
    }

    @Test
    fun `undotAtom on undotted is noop`() {
        assertEquals("1234", undotAtom("1234"))
    }

    @Test
    fun `dotAtom undotAtom roundtrip`() {
        val raw = "170141184507932790143209384169177088000"
        assertEquals(raw, undotAtom(dotAtom(raw)))
    }
}
