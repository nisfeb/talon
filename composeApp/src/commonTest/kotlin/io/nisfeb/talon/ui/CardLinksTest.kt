package io.nisfeb.talon.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CardLinksTest {
    private fun links(s: AnnotatedString) =
        s.getLinkAnnotations(0, s.length).map { s.text.substring(it.start, it.end) to (it.item as LinkAnnotation.Url).url }

    @Test
    fun `an @p opens that person's chat`() {
        val s = AnnotatedString("Andrea is ~sampel-palnet, and ~zod hosts it.").withTalonLinks(emptyMap())
        assertEquals(listOf("~sampel-palnet" to "dm:~sampel-palnet", "~zod" to "dm:~zod"), links(s))
    }

    @Test
    fun `a name the answer looked up opens their chat, the longest name first`() {
        val names = mapOf("Andrea" to "~sampel-palnet", "Andrea Egan" to "~sampel-palnet", "Linus" to "~linus-ship")
        val s = AnnotatedString("Ask Andrea Egan about Linus; andrea knows.").withTalonLinks(names)
        assertEquals(
            listOf("Andrea Egan" to "dm:~sampel-palnet", "Linus" to "dm:~linus-ship", "andrea" to "dm:~sampel-palnet"),
            links(s).sortedBy { s.text.indexOf(it.first) },
        )
    }

    @Test
    fun `a bare link opens where it points, the sentence's full stop left out`() {
        val s = AnnotatedString("See https://example.com/a. Or www.example.org, or talon://mail/t1.").withTalonLinks(emptyMap())
        assertEquals(
            listOf("https://example.com/a" to "https://example.com/a", "www.example.org" to "https://www.example.org", "talon://mail/t1" to "talon://mail/t1"),
            links(s),
        )
    }

    @Test
    fun `a link already in the text is left as it is, and nothing is linked twice`() {
        val md = inlineAnnotated("[Tom on Tuesday](talon://chat/x) said ~zod", Color.Gray)
        val s = md.withTalonLinks(mapOf("Tom" to "~tom-ship"))
        assertEquals(listOf("Tom on Tuesday" to "talon://chat/x", "~zod" to "dm:~zod"), links(s))
    }

    @Test
    fun `a word that only looks like an @p is not one`() {
        assertEquals(emptyList(), links(AnnotatedString("costs ~two dollars, ~approx").withTalonLinks(emptyMap())))
    }

    @Test
    fun `dm addresses round trip, and nothing else is one`() {
        assertEquals("~zod", dmShip(dmLink("~zod")))
        assertNull(dmShip("https://x"))
        assertNull(dmShip("dm:not-a-ship"))
    }
}
