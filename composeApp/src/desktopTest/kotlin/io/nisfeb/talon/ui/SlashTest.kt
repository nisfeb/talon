package io.nisfeb.talon.ui

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins parseSlash + detectSlashTrigger. Both are simple but feed
 * every slash-command path the composer dispatches; a regression
 * here silently breaks autocomplete or routes garbage into the
 * normal send path.
 */
class SlashTest {

    @Test
    fun `parseSlash returns null for plain text`() {
        assertNull(parseSlash("hello world"))
    }

    @Test
    fun `parseSlash returns null for empty input`() {
        assertNull(parseSlash(""))
    }

    @Test
    fun `parseSlash returns null for bare slash`() {
        // Just "/" has empty body → no command.
        assertNull(parseSlash("/"))
        assertNull(parseSlash("   /   "))
    }

    @Test
    fun `parseSlash lowercases the command and trims leading whitespace`() {
        val r = parseSlash("  /HN  ")
        assertNotNull(r)
        assertEquals("hn", r!!.cmd)
        assertEquals(emptyList(), r.args)
    }

    @Test
    fun `parseSlash splits args on any whitespace`() {
        val r = parseSlash("/cmd  one   two\tthree")
        assertNotNull(r)
        assertEquals("cmd", r!!.cmd)
        assertEquals(listOf("one", "two", "three"), r.args)
    }

    @Test
    fun `parseSlash preserves arg case`() {
        // Args are user content; only the command name is normalized.
        val r = parseSlash("/pet ~Zod-bus MyName")
        assertNotNull(r)
        assertEquals(listOf("~Zod-bus", "MyName"), r!!.args)
    }

    @Test
    fun `detectSlashTrigger fires inside the command name segment`() {
        val t = detectSlashTrigger("/cal", cursor = 4)
        assertNotNull(t)
        assertEquals("cal", t!!.query)
    }

    @Test
    fun `detectSlashTrigger fires immediately after the slash`() {
        // cursor=1 after just "/" — query is empty so picker shows full catalog.
        val t = detectSlashTrigger("/", cursor = 1)
        assertNotNull(t)
        assertEquals("", t!!.query)
    }

    @Test
    fun `detectSlashTrigger returns null after whitespace`() {
        // Once the user typed a space (entering args), the picker closes.
        assertNull(detectSlashTrigger("/cal foo", cursor = 5))
        assertNull(detectSlashTrigger("/cal foo", cursor = 8))
    }

    @Test
    fun `detectSlashTrigger returns null when text doesn't start with slash`() {
        assertNull(detectSlashTrigger("hello", cursor = 5))
        assertNull(detectSlashTrigger(" /cal", cursor = 5))
    }

    @Test
    fun `detectSlashTrigger returns null at cursor 0 or out of bounds`() {
        assertNull(detectSlashTrigger("/cal", cursor = 0))
        assertNull(detectSlashTrigger("/cal", cursor = 999))
    }
}
