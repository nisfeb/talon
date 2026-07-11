package io.nisfeb.talon.ui

import io.nisfeb.talon.ui.screens.DayKeyer
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * DayKeyer caches the current local-day epoch-ms window and only
 * re-converts on a day boundary. These assert the window arithmetic:
 * same local day → same key, across midnight → different key, and the
 * fast in-window path agrees with a fresh keyer.
 */
class DayKeyerTest {
    private val utc = TimeZone.UTC
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime(y, mo, d, h, mi, 0).toInstant(utc).toEpochMilliseconds()

    @Test
    fun sameLocalDay_sameKey() {
        val k = DayKeyer(utc)
        val early = k.keyFor(ms(2026, 7, 11, 0, 0))   // exactly midnight (inclusive start)
        val noon = k.keyFor(ms(2026, 7, 11, 12, 30))
        val late = k.keyFor(ms(2026, 7, 11, 23, 59))
        assertEquals(early, noon)
        assertEquals(noon, late)
    }

    @Test
    fun crossingMidnight_changesKey() {
        val k = DayKeyer(utc)
        val day1 = k.keyFor(ms(2026, 7, 11, 23, 59))
        val day2 = k.keyFor(ms(2026, 7, 12, 0, 0))    // next midnight (exclusive end → new day)
        assertNotEquals(day1, day2)
    }

    @Test
    fun windowedResultMatchesFreshKeyer() {
        // A keyer walking a full day must produce the same key a
        // fresh keyer produces for that day in isolation — i.e. the
        // in-window fast path never disagrees with a real conversion.
        val walked = DayKeyer(utc)
        walked.keyFor(ms(2026, 7, 11, 1, 0))
        val walkedNoon = walked.keyFor(ms(2026, 7, 11, 12, 0))
        val fresh = DayKeyer(utc).keyFor(ms(2026, 7, 11, 12, 0))
        assertEquals(fresh, walkedNoon)
    }
}
