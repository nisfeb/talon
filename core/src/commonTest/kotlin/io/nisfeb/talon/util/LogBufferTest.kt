package io.nisfeb.talon.util

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The recent log lines a bug report carries, on every platform. */
class LogBufferTest {
    @AfterTest
    fun clear() = LogBuffer.clearForTest()

    @Test
    fun keepsTheNewestLinesUpToItsCap() {
        LogBuffer.clearForTest()
        repeat(LogBuffer.MAX_LINES + 20) { LogBuffer.add("INFO ", "T", "line $it") }
        val kept = LogBuffer.recent()
        assertEquals(LogBuffer.MAX_LINES, kept.size)
        assertTrue(kept.first().endsWith("line 20"), kept.first())
        assertTrue(kept.last().endsWith("line ${LogBuffer.MAX_LINES + 19}"), kept.last())
    }

    @Test
    fun aLineSaysItsLevelTagAndCauseAndARunawayOneIsCut() {
        LogBuffer.clearForTest()
        LogBuffer.add("WARN ", "Mail", "send failed", IllegalStateException("no ship"))
        LogBuffer.add("INFO ", "Dump", "x".repeat(5_000))
        val (warn, dump) = LogBuffer.recent()
        assertTrue("WARN  [Mail] send failed (IllegalStateException: no ship)" in warn, warn)
        assertTrue(dump.length <= LogBuffer.MAX_LINE + 2, "cut to ${dump.length}")
    }
}
