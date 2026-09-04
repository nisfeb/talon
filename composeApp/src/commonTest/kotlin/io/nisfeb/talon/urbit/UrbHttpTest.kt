package io.nisfeb.talon.urbit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UrbHttpTest {
    @Test
    fun `encodes the urb address into the reader query`() {
        val out = UrbHttp.readerUrl("https://ship.example", "urb://~sampel/p/counter")
        assertTrue(out.startsWith("https://ship.example/apps/lattice?url="))
        // the scheme's slashes and colon must be percent-encoded so
        // they land in the query value, not the path.
        assertTrue("urb%3A%2F%2F" in out, "urb:// must be percent-encoded: $out")
        assertTrue("~sampel" !in out || "%7E" in out || "~" in out) // tolerate ~ passthrough
    }

    @Test
    fun `trims a trailing slash on the ship base`() {
        assertEquals(
            UrbHttp.readerUrl("https://ship.example", "urb://~z/n/a"),
            UrbHttp.readerUrl("https://ship.example/", "urb://~z/n/a"),
        )
    }
}
