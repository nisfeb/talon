package io.nisfeb.talon.urbit

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A link written as its own address is drawn cut short, and that cut
 * reached Copy text, a new event's notes and the assistant's reads in
 * place of the address. [StoryCache.textFor] gives the whole of it back.
 */
class TextForLinksTest {
    private val long = "https://example.com/articles/2026/10/a-very-long-slug"

    private fun said(id: String, inline: String) = StoryCache.textFor("textfor-$id", """[{"inline":[$inline]}]""")

    @Test
    fun `a link shown as its cut address reads as the whole address`() {
        assertEquals("see $long", said("link", """"see ",{"link":{"href":"$long","content":"$long"}}"""))
        assertEquals("see $long", said("bare-link", """"see ",{"link":{"href":"$long"}}"""))
    }

    @Test
    fun `an address typed into plain words reads whole too`() {
        assertEquals("read $long now", said("plain", "\"read $long now\""))
        val urb = "urb://~zod/lattice/notes/one/two"
        assertEquals("open $urb please", said("urb", "\"open $urb please\""))
    }

    @Test
    fun `a named link keeps its name and a short address is left as it is`() {
        assertEquals("the article", said("named", """{"link":{"href":"$long","content":"the article"}}"""))
        assertEquals("go https://x.test/a", said("short", """"go ",{"link":{"href":"https://x.test/a","content":"https://x.test/a"}}"""))
        assertEquals("no links here", said("none", "\"no links here\""))
    }

    @Test
    fun `two links in one line each come back whole and the words between stay`() {
        val other = "https://other.example/a/very/long/path/indeed"
        assertEquals(
            "$long and $other",
            said("two", """{"link":{"href":"$long","content":"$long"}}," and ",{"link":{"href":"$other","content":"$other"}}"""),
        )
    }
}
