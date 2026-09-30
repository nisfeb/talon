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

    // Lattice's fetch sends an HTML page as it is, labelled gmi. Read as
    // gemtext, the card showed its style sheet: "head {display:flex".
    // These are cut from the real pages at urb://~nisfeb/site/auspex/index
    // and urb://~nisfeb/site/talon/index.
    private val auspex = """
        <!-- source of auspex.example: this page is served from lattice on ~sampel -->
        <style>.cbar{display:none}</style>
        <meta name="description" content="The Auspex wire protocol, version 1. Signed, forwardable, per-message verifiable mail between Urbit ships.">
        <style>
        head {display:flex; gap: 1em}
        </style>
        <h1>The Auspex Mail Protocol, Version 1</h1>
        <p>Mail between ships.</p>
        <script>document.title='The Auspex Mail Protocol, Version 1';</script>
    """.trimIndent()

    @Test
    fun `an HTML page is read as a web page, its title and description, never its style sheet`() {
        val u = UrbUnfurlCache.unfurlOf("urb://~nisfeb/site/auspex/index", auspex)
        assertEquals("The Auspex Mail Protocol, Version 1", u.title)
        assertEquals("The Auspex wire protocol, version 1. Signed, forwardable, per-message verifiable mail between Urbit ships.", u.snippet)
    }

    @Test
    fun `a page's Open Graph title comes first`() {
        val talon = """
            <!-- source of talon.example -->
            <style>.cb{color:red}</style>
            <meta property="og:title" content="Talon: chat and calls for Urbit">
            <h1>Native chat for Urbit.</h1>
        """.trimIndent()
        assertEquals("Talon: chat and calls for Urbit", UrbUnfurlCache.unfurlOf("urb://~nisfeb/site/talon/index", talon).title)
    }

    @Test
    fun `with no head to read, the first heading and paragraph, as text`() {
        val u = UrbUnfurlCache.unfurlOf("urb://~z/p", "<style>p{margin:0}</style><h1>Tea &amp; toast</h1><script>var x=1</script><p>Steep it <b>long</b>.</p>")
        assertEquals("Tea & toast", u.title)
        assertEquals("Steep it long.", u.snippet)
    }

    @Test
    fun `gemtext that happens to open with a bracket is still gemtext`() {
        val u = UrbUnfurlCache.unfurlOf("urb://~z/n/x", "<3 to everyone who came\n# Thanks")
        assertEquals("Thanks", u.title)
        assertEquals("<3 to everyone who came", u.snippet)
    }

    @Test
    fun `empty body yields no title or snippet`() {
        val u = UrbUnfurlCache.unfurlOf("urb://~z", "")
        assertNull(u.title)
        assertNull(u.snippet)
    }
}
