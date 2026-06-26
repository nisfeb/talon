package io.nisfeb.talon.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins the Brave Search response parsing: result extraction, `<strong>`
 *  highlight stripping, the max cap, and the graceful sentinels for empty
 *  / missing / malformed bodies. */
class BraveSearchParseTest {

    @Test
    fun `formats results and strips highlight tags`() {
        val json = """
            {"web":{"results":[
              {"title":"Kotlin <strong>coroutines</strong>","url":"https://kotlinlang.org/x","description":"A <strong>guide</strong> to coroutines."},
              {"title":"Second","url":"https://example.com","description":"More text."}
            ]}}
        """.trimIndent()
        val out = parseBraveResults(json, 10)
        assertTrue("1. Kotlin coroutines" in out, out)
        assertTrue("https://kotlinlang.org/x" in out, out)
        assertTrue("A guide to coroutines." in out, out)
        assertTrue("<strong>" !in out, "tags should be stripped: $out")
        assertTrue("2. Second" in out, out)
    }

    @Test
    fun `respects the max cap`() {
        val json = """
            {"web":{"results":[
              {"title":"a","url":"https://a","description":"x"},
              {"title":"b","url":"https://b","description":"y"},
              {"title":"c","url":"https://c","description":"z"}
            ]}}
        """.trimIndent()
        val out = parseBraveResults(json, 2)
        assertTrue("1. a" in out && "2. b" in out, out)
        assertTrue("3. c" !in out, "should cap at 2: $out")
    }

    @Test
    fun `empty and missing results yield the no-results sentinel`() {
        assertEquals("No web results.", parseBraveResults("""{"web":{"results":[]}}""", 5))
        assertEquals("No web results.", parseBraveResults("""{"query":{"original":"hi"}}""", 5))
    }

    @Test
    fun `malformed body is reported, not thrown`() {
        assertEquals("Web search returned an unreadable response.", parseBraveResults("not json", 5))
    }
}
