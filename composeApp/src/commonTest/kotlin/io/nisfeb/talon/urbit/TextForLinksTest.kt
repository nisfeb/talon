package io.nisfeb.talon.urbit

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A link written as its own address is drawn cut short, and that cut
 * reached Copy text, a new event's notes and the assistant's reads in
 * place of the address. [StoryCache.copyTextFor] gives the whole of it
 * back. [StoryCache.textFor] is the preview and keeps the cut (B6-6).
 */
class TextForLinksTest {
    private val long = "https://example.com/articles/2026/10/a-very-long-slug"

    private fun said(id: String, inline: String) = StoryCache.copyTextFor("textfor-$id", """[{"inline":[$inline]}]""")

    @Test
    fun `a preview keeps a long address cut and a named link its name`() {
        val json = """[{"inline":["see ",{"link":{"href":"$long","content":"$long"}}]}]"""
        assertEquals("see ${shortLinkLabel(long)}", StoryCache.textFor("preview-link", json))
        // The copy of the same post, read after, is not the cached preview.
        assertEquals("see $long", StoryCache.copyTextFor("preview-link", json))
        assertEquals("see ${shortLinkLabel(long)}", StoryCache.textFor("preview-link", json))
        val named = """[{"inline":[{"link":{"href":"$long","content":"the article"}}]}]"""
        assertEquals("the article", StoryCache.textFor("preview-named", named))
    }

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

    // Straight to the function.
    @Test
    fun `words with no link come back as they are`() {
        assertEquals("plain words", withAddresses(androidx.compose.ui.text.AnnotatedString("plain words")))
    }

    @Test
    fun `a cut address between words comes back whole in its place`() {
        val text = androidx.compose.ui.text.buildAnnotatedString {
            append("see ")
            pushStringAnnotation(URL_TAG, long)
            append(shortLinkLabel(long))
            pop()
            append(" today")
        }
        assertEquals("see $long today", withAddresses(text))
    }
}
