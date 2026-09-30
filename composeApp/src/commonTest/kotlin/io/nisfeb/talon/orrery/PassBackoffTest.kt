package io.nisfeb.talon.orrery

import kotlin.test.Test
import kotlin.test.assertEquals

/** A failed pass is tried again later each time, not every ten minutes. */
class PassBackoffTest {
    @Test
    fun `the wait doubles from ten minutes and stops at five hours and a bit`() {
        val min = 60_000L
        assertEquals(listOf(10, 20, 40, 80, 160, 320, 320).map { it * min }, (1..7).map { OrreryRepo.backoff(it) })
    }
}
