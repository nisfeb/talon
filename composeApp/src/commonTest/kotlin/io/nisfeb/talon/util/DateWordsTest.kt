package io.nisfeb.talon.util

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class DateWordsTest {
    // The calendar's editor showed "2026-07-10" for a due date or an until.
    @Test
    fun `a date reads in words, a yearly one without its year`() {
        assertEquals("Fri, Jul 10, 2026", formatDate(LocalDate(2026, 7, 10)))
        assertEquals("Jul 10", formatDateNoYear(LocalDate(2026, 7, 10)))
    }
}
