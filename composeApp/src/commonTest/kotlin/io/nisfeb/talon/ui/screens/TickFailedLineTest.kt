package io.nisfeb.talon.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals

/** A tick the ship did not take, said as the one it was: an untick read "was not ticked". */
class TickFailedLineTest {
    @Test
    fun `a failed tick and a failed untick are each said as what they were`() {
        assertEquals("\"Buy milk\" was not ticked: the ship did not take it.", tickFailedLine("Buy milk", done = true))
        assertEquals("\"Buy milk\" was not unticked: the ship did not take it.", tickFailedLine("Buy milk", done = false))
        assertEquals("\"The task\" was not unticked: the ship did not take it.", tickFailedLine("", done = false))
    }
}
