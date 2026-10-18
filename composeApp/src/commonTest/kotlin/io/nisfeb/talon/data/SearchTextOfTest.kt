package io.nisfeb.talon.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What search keeps of a message: the words shown, then what they stand
 * for and do not show, a link's address and a mention's @p (review
 * A5-14). An excerpt reads the words alone.
 */
class SearchTextOfTest {
    private val long = "https://example.test/a/very/long/path/to/a/page"

    private fun inline(vararg spans: String) = """[{"inline":[${spans.joinToString(",")}]}]"""
    private fun link(href: String, content: String) = """{"link":{"href":"$href","content":"$content"}}"""

    @Test
    fun `an address named otherwise or cut short is kept behind the words`() {
        val text = searchTextOf(inline(link("https://x.test/plan", "the plan"), "\" and \"", link(long, long)), title = "Plans")
        val shown = shownWordsOf(text)
        assertTrue(shown.startsWith("Plans\nthe plan and "), shown)
        assertTrue("https://x.test/plan" !in shown && long !in shown, shown)
        assertTrue(text.endsWith("https://x.test/plan $long"), text)
    }

    @Test
    fun `what the words already show is not kept twice`() {
        val text = searchTextOf(inline(link("https://x.test/a", "https://x.test/a"), "\" with \"", """{"ship":"~nec"}"""), title = null)
        assertEquals(text, shownWordsOf(text), "nothing behind: $text")
        assertEquals("https://x.test/a with ~nec", text)
    }

    @Test
    fun `a message with no links or mentions is its words alone`() {
        assertEquals("Plans\nhello", searchTextOf(inline("\"hello\""), title = "Plans"))
        assertEquals("hello", searchTextOf(inline("\"hello\""), title = "  "), "a blank title is none")
        assertEquals("hello", shownWordsOf("hello"))
    }

    @Test
    fun `a link in a table cell is kept behind the words too`() {
        val table = "| Name | Where |\\n| --- | --- |\\n| Plan | [the plan](https://x.test/plan) |"
        val text = searchTextOf(inline("\"$table\""), title = null)
        assertTrue("the plan" in shownWordsOf(text), text)
        assertTrue(text.endsWith("\u001Fhttps://x.test/plan"), text)
    }

    @Test
    fun `a link preview is found by its title and address`() {
        val text = searchTextOf("""[{"block":{"link":{"url":"https://p.test/a","meta":{"title":"A page"}}}}]""", title = null)
        assertEquals("A page https://p.test/a", text)
    }
}
