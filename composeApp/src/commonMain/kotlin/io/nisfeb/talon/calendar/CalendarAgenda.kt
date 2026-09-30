package io.nisfeb.talon.calendar

import io.nisfeb.talon.ui.CalendarRange
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
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
    val live = rows.map { it to it.bounds(zone) }.filter { (_, b) -> b.second > nowMs }
        .sortedWith(compareBy({ it.second.first }, { it.second.second }))
    val until = rangeEnd(range, nowMs, zone) ?: return live.take(1).map { it.first }
    return live.filter { (_, b) -> b.first < until }.map { it.first }
}

/**
 * What the widget calls itself. A window a day wide reaches into
 * tomorrow, so it stops claiming to be today.
 */
fun agendaHeading(range: CalendarRange): String =
    if (range == CalendarRange.NEXT_DAY) "Coming Up" else "Today"

/**
 * The day a row belongs to in the list. Anything already under way is
 * today's, whenever it began, so the line the list draws where the day
 * turns over falls above the first event of tomorrow and nowhere else.
 */
fun agendaDay(startMs: Long, nowMs: Long, zone: TimeZone): LocalDate =
    Instant.fromEpochMilliseconds(maxOf(startMs, nowMs)).toLocalDateTime(zone).date

/** Where the widget's window ends, in unix ms; null for "next only", which has no end. */
fun rangeEnd(range: CalendarRange, nowMs: Long, zone: TimeZone): Long? = when (range) {
    CalendarRange.NEXT_ONLY -> null
    CalendarRange.NEXT_3_HOURS -> nowMs + 3 * HOUR_MS
    CalendarRange.NEXT_6_HOURS -> nowMs + 6 * HOUR_MS
    CalendarRange.NEXT_DAY -> nowMs + 24 * HOUR_MS
    CalendarRange.REST_OF_DAY -> midnightAfter(nowMs, zone)
}

private fun midnightAfter(ms: Long, zone: TimeZone): Long {
    val day = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone).date
    return day.plus(1, DateTimeUnit.DAY).atTime(LocalTime(0, 0)).toInstant(zone).toEpochMilliseconds()
}

private const val HOUR_MS = 60 * 60 * 1000L

/** The tasks list's order: open ones soonest due first, undated last, then by name. */
fun taskOrder(tasks: List<CalendarTask>): List<CalendarTask> =
    tasks.sortedWith(compareBy({ it.dueMs ?: Long.MAX_VALUE }, { it.name.lowercase() }))

/** The task's due day, as the calendar's page reads it: the UTC date of due_ms. */
fun CalendarTask.dueDate(): LocalDate? = dueMs?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date }

/**
 * The open tasks the widget shows for [range]: due on a day the
 * window reaches, or already overdue. A task has a day, not a
 * moment, so a window that crosses midnight takes in tomorrow's
 * tasks too, and "next only" means today.
 */
fun tasksInRange(tasks: List<CalendarTask>, range: CalendarRange, nowMs: Long, zone: TimeZone): List<CalendarTask> {
    // A real end is exclusive, so its last day ends a millisecond
    // before it; "next only" has no end to subtract from, and at
    // exactly midnight nowMs - 1 would be yesterday. Its day is today.
    val lastDay = when (val end = rangeEnd(range, nowMs, zone)) {
        null -> Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date
        else -> Instant.fromEpochMilliseconds(end - 1).toLocalDateTime(zone).date
    }
    return taskOrder(tasks.filter { !it.done && (it.dueDate()?.let { d -> d <= lastDay } ?: false) })
}
