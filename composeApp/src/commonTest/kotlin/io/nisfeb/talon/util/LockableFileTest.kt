package io.nisfeb.talon.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * iOS keeps Talon's files locked while the device is. A store built then
 * (a call's push) read its file as nothing, and its next save wrote that
 * nothing over the saved ships.
 */
class LockableFileTest {
    private var disk: String? = """{"ships":1}"""
    private var locked = true
    private val writes = mutableListOf<String>()
    private val file = LockableFile(
        "sessions.json",
        readText = { if (locked) null else disk },
        exists = { disk != null },
        writeText = { disk = it; writes += it },
    )

    @Test fun `a file that is there but unreadable is not written over`() {
        assertNull(file.text())
        assertFalse(file.known)
        assertFalse(file.write("{}"))
        assertEquals(emptyList(), writes)
        assertEquals("""{"ships":1}""", disk)
    }

    @Test fun `once a read works the file is known and written`() {
        file.text()
        locked = false
        assertEquals("""{"ships":1}""", file.text())
        assertTrue(file.known)
        assertTrue(file.write("""{"ships":2}"""))
        assertEquals("""{"ships":2}""", disk)
    }

    @Test fun `an absent file is known at once and written`() {
        disk = null
        assertNull(file.text())
        assertTrue(file.known)
        assertTrue(file.write("{}"))
        assertEquals(listOf("{}"), writes)
    }

    @Test fun `a write meant to replace it goes over an unread file`() {
        file.text()
        assertTrue(file.write("{}", overUnread = true))
        assertEquals("{}", disk)
        assertTrue(file.known)
        assertTrue(file.write("{\"a\":1}"))
    }

    @Test fun `a write before any read is refused while the file is there`() {
        assertFalse(file.write("{}"))
        disk = null
        assertTrue(file.write("{}"))
    }
}
