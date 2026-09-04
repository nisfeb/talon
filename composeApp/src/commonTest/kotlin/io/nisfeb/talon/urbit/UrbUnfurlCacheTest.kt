package io.nisfeb.talon.urbit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UrbUnfurlCacheTest {
    @Test
    fun `title is the first heading, snippet the first prose line`() {
        val gmi = """
            # Trail Cleanup
            => urb://~sampel/n/map  the route
            Saturday at 9am, north gate. Bring gloves.
            more text here
        """.trimIndent()
        val u = UrbUnfurlCache.unfurlOf("urb://~sampel/n/cleanup", gmi)
        assertEquals("Trail Cleanup", u.title)
        assertEquals("Saturday at 9am, north gate. Bring gloves.", u.snippet)
    }

    @Test
    fun `skips link lines and code fences for the snippet`() {
        val gmi = "=> urb://~z/n/a link\n```\ncode\n```\nreal prose"
        val u = UrbUnfurlCache.unfurlOf("urb://~z/n/x", gmi)
        assertNull(u.title) // no heading
        assertEquals("real prose", u.snippet)
    }

    @Test
    fun `empty body yields no title or snippet`() {
        val u = UrbUnfurlCache.unfurlOf("urb://~z", "")
        assertNull(u.title)
        assertNull(u.snippet)
    }
}
