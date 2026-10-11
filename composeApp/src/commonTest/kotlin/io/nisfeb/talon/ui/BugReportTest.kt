package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a bug report from Settings, About says. */
class BugReportTest {
    private val about = listOf("Version" to "1.8.17 (build 505)", "Platform" to "Linux")

    @Test
    fun `the subject is the first line of what went wrong, cut short`() {
        assertEquals("Talon bug: Calls drop", bugReport("  Calls drop\nafter a minute", about, null).subject)
        val long = bugReport("a".repeat(100), about, null).subject
        assertEquals("Talon bug: " + "a".repeat(60) + "…", long)
    }

    @Test
    fun `the body says what the app is and whether logs went, which travel as a file`() {
        val r = bugReport("Calls drop", about, listOf("one", "two"))
        assertEquals("Calls drop\n\nVersion: 1.8.17 (build 505)\nPlatform: Linux\nLogs: 2 lines, in $BUG_REPORT_LOG_FILE", r.body)
        assertEquals("one\ntwo\n", r.logs!!.decodeToString())
        val none = bugReport("Calls drop", about, null)
        assertTrue(none.body.endsWith("Logs: not sent"), none.body)
        assertNull(none.logs)
    }
}
