package io.nisfeb.talon.ai

import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the fetch_url SSRF guard and HTML-to-text reduction. The address
 * checks use IP literals (InetAddress.getByName on a literal does NOT hit
 * DNS) so the test runs offline.
 */
class UrlFetcherTest {

    @Test
    fun `blocks internal hostname literals`() {
        assertTrue(isBlockedName("localhost"))
        assertTrue(isBlockedName("LOCALHOST"))
        assertTrue(isBlockedName("printer.local"))
        assertTrue(isBlockedName("app.localhost"))
        assertTrue(isBlockedName("fc00::1"))   // IPv6 ULA — InetAddress flags miss it
        assertTrue(isBlockedName(""))
        assertFalse(isBlockedName("example.com"))
        assertFalse(isBlockedName("hacker-news.firebaseio.com"))
    }

    @Test
    fun `blocks internal IP addresses, allows public ones`() {
        // Loopback, cloud metadata, RFC1918 private ranges.
        assertTrue(isInternalAddress(InetAddress.getByName("127.0.0.1")))
        assertTrue(isInternalAddress(InetAddress.getByName("169.254.169.254")))
        assertTrue(isInternalAddress(InetAddress.getByName("10.1.2.3")))
        assertTrue(isInternalAddress(InetAddress.getByName("192.168.0.1")))
        assertTrue(isInternalAddress(InetAddress.getByName("172.16.5.5")))
        assertTrue(isInternalAddress(InetAddress.getByName("::1")))
        assertTrue(isInternalAddress(InetAddress.getByName("0.0.0.0")))
        // IPv6 unique-local (fc00::/7) — not caught by isSiteLocalAddress.
        assertTrue(isInternalAddress(InetAddress.getByName("fd12:3456:789a::1")))
        assertTrue(isInternalAddress(InetAddress.getByName("fc00::1")))
        // Public addresses pass.
        assertFalse(isInternalAddress(InetAddress.getByName("8.8.8.8")))
        assertFalse(isInternalAddress(InetAddress.getByName("1.1.1.1")))
        assertFalse(isInternalAddress(InetAddress.getByName("172.32.0.1"))) // outside 172.16/12
        assertFalse(isInternalAddress(InetAddress.getByName("2001:4860:4860::8888"))) // public IPv6
    }

    @Test
    fun `htmlToText strips tags and scripts, decodes entities`() {
        val html = """
            <html><head><style>.x{color:red}</style><script>evil()</script></head>
            <body><h1>Title</h1><p>Hello &amp; welcome</p><p>Line two &lt;tag&gt;</p>
            <!-- comment --><div>a</div><div>b</div></body></html>
        """.trimIndent()
        val out = htmlToText(html)
        assertTrue("Title" in out, out)
        assertTrue("Hello & welcome" in out, out)
        assertTrue("Line two <tag>" in out, out)
        assertTrue("evil()" !in out, "script body must be dropped: $out")
        assertTrue("color:red" !in out, "style body must be dropped: $out")
        assertTrue("<p>" !in out && "<div>" !in out, "tags must be stripped: $out")
        // Block elements become line breaks, so a and b aren't glued.
        assertFalse("ab" in out.replace(" ", ""), out)
    }

    @Test
    fun `htmlToText collapses whitespace`() {
        assertEquals("one two", htmlToText("<span>one</span>   <span>two</span>"))
    }

    /**
     * The assistantOn() gate is the load-bearing web-egress control: with
     * the assistant off, neither web tool may touch the network. Keys are
     * set here so the off-sentinel can ONLY come from that gate firing first
     * — if a refactor drops the `if (!assistantOn())` line, both calls fall
     * through to HTTP and this test fails (no sentinel). Hermetic while the
     * gate stands: nothing connects.
     */
    @Test
    fun `web tools refuse when the assistant is off`() = runBlocking {
        val off = AiSettings.Config(
            provider = AiSettings.Provider.Anthropic,
            apiKey = "sk-set",
            model = null,
            agentEnabled = false,
            askUrbitEnabled = false,
            braveApiKey = "brave-set",
        )
        assertFalse(off.assistantOn())
        assertTrue("is off in Settings" in BraveSearchClient { off }.search("anything", 5))
        assertTrue("is off in Settings" in UrlFetcher { off }.fetch("https://example.com"))
    }
}
