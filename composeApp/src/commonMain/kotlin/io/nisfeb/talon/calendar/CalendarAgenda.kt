package io.nisfeb.talon.calendar

import io.nisfeb.talon.ui.CalendarRange
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * The rows the Today widget shows for [range] at [nowMs]: whatever is
 * under way or starts before the range ends, in time order. "Next
 * only" is the first of those, however far off; "rest of today" runs
 * to midnight in [zone].
 */
fun agenda(rows: List<CalendarRow>, range: CalendarRange, nowMs: Long, zone: TimeZone): List<CalendarRow> {
    val live = rows.filter { it.r > nowMs }.sortedWith(compareBy({ it.l }, { it.r }))
    val until = when (range) {
        CalendarRange.NEXT_ONLY -> return live.take(1)
        CalendarRange.NEXT_3_HOURS -> nowMs + 3 * HOUR_MS
        CalendarRange.NEXT_6_HOURS -> nowMs + 6 * HOUR_MS
        CalendarRange.NEXT_DAY -> nowMs + 24 * HOUR_MS
        CalendarRange.REST_OF_DAY -> {
            val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date
            today.plus(1, DateTimeUnit.DAY).atTime(LocalTime(0, 0)).toInstant(zone).toEpochMilliseconds()
        }
    }
    return live.filter { it.l < until }
}

private const val HOUR_MS = 60 * 60 * 1000L
