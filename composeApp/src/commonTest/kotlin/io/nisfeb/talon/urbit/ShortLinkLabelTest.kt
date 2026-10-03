package io.nisfeb.talon.urbit

import kotlin.test.Test
import kotlin.test.assertEquals

/** A long address ran across the message; it shows cut short, and still goes to the whole of it. */
class ShortLinkLabelTest {
    @Test
    fun `a long address is cut to twenty characters, a short one is left as written`() {
        assertEquals("example.com/articles…", shortLinkLabel("https://example.com/articles/2026/10/a-very-long-slug"))
        assertEquals("https://x.test/a", shortLinkLabel("https://x.test/a"))
        assertEquals("urb://~zod/lattice/n…", shortLinkLabel("urb://~zod/lattice/notes/one/two"))
    }
}
