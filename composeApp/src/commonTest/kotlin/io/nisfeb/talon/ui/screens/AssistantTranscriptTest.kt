package io.nisfeb.talon.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the transcript grouping that drives newest-exchange-first rendering:
 * exchanges split on a [Line.You], order is preserved WITHIN an exchange
 * (so question→log→answer never scrambles), and reversing the exchange list
 * puts the latest exchange on top.
 */
class AssistantTranscriptTest {

    @Test
    fun `empty transcript yields no exchanges`() {
        assertEquals(emptyList(), toExchanges(emptyList()))
    }

    @Test
    fun `each You starts a new exchange and inner order is preserved`() {
        val lines = listOf(
            Line.You("q1"), Line.Note("tool"), Line.Said("a1"),
            Line.You("q2"), Line.Said("a2"),
        )
        val ex = toExchanges(lines)
        assertEquals(2, ex.size)
        assertEquals(listOf(Line.You("q1"), Line.Note("tool"), Line.Said("a1")), ex[0])
        assertEquals(listOf(Line.You("q2"), Line.Said("a2")), ex[1])
    }

    @Test
    fun `reversed exchanges put the newest turn first`() {
        val lines = listOf(
            Line.You("old"), Line.Said("oldA"),
            Line.You("new"), Line.Said("newA"),
        )
        val newestFirst = toExchanges(lines).asReversed()
        assertEquals(Line.You("new"), newestFirst.first().first())
        assertEquals(Line.You("old"), newestFirst.last().first())
    }

    @Test
    fun `a leading run without a You is its own group`() {
        val lines = listOf(Line.Note("boot"), Line.You("q"), Line.Said("a"))
        val ex = toExchanges(lines)
        assertEquals(listOf(Line.Note("boot")), ex[0])
        assertEquals(listOf(Line.You("q"), Line.Said("a")), ex[1])
    }
}
