package io.nisfeb.talon.ai

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class DailyDigestScheduleTest {

    private val nyc = ZoneId.of("America/New_York")

    private fun at(zone: ZoneId, year: Int, month: Int, day: Int, h: Int, m: Int): Instant =
        ZonedDateTime.of(year, month, day, h, m, 0, 0, zone).toInstant()

    @Test fun `next is later today when target hasn't passed`() {
        val now = at(nyc, 2026, 4, 26, 5, 30)
        val next = DailyDigestSchedule.nextFireMs(now, 6, 0, nyc)
        assertEquals(at(nyc, 2026, 4, 26, 6, 0).toEpochMilli(), next)
    }

    @Test fun `next is tomorrow when target has already passed`() {
        val now = at(nyc, 2026, 4, 26, 7, 0)
        val next = DailyDigestSchedule.nextFireMs(now, 6, 0, nyc)
        assertEquals(at(nyc, 2026, 4, 27, 6, 0).toEpochMilli(), next)
    }

    @Test fun `next is tomorrow when now equals target`() {
        // Spec §Schedule: equal-to-now counts as past, fire tomorrow.
        val now = at(nyc, 2026, 4, 26, 6, 0)
        val next = DailyDigestSchedule.nextFireMs(now, 6, 0, nyc)
        assertEquals(at(nyc, 2026, 4, 27, 6, 0).toEpochMilli(), next)
    }

    @Test fun `midnight rollover`() {
        val now = at(nyc, 2026, 4, 26, 23, 30)
        val next = DailyDigestSchedule.nextFireMs(now, 0, 5, nyc)
        assertEquals(at(nyc, 2026, 4, 27, 0, 5).toEpochMilli(), next)
    }

    @Test fun `DST forward — 2am does not exist on spring-forward day`() {
        // 2026-03-08 is the US DST forward day; 2:00 EST jumps to 3:00 EDT.
        val now = at(nyc, 2026, 3, 7, 23, 0)
        val next = DailyDigestSchedule.nextFireMs(now, 2, 30, nyc)
        assert(next > now.toEpochMilli())
    }

    @Test fun `DST back — 1_30am happens twice on fall-back day`() {
        // 2026-11-01 is the US DST back day. 1:30 AM occurs twice (EDT then EST).
        val now = at(nyc, 2026, 11, 1, 0, 30)
        val next = DailyDigestSchedule.nextFireMs(now, 1, 30, nyc)
        assert(next > now.toEpochMilli())
    }
}
