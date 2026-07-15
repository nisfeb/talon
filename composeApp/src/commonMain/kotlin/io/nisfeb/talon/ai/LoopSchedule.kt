package io.nisfeb.talon.ai

import io.nisfeb.talon.data.LoopEntity
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Pure scheduling math for user loops. Two shapes:
 *  - `"interval"`: every N minutes since the last run — needs only the
 *    last run and the interval, no timezone.
 *  - `"weekly"`: at a time-of-day on selected weekdays, in the device's
 *    zone (like the daily digest). Empty day set = every day.
 *
 * No side effects — same inputs, same answer. The Android alarm
 * scheduler and the desktop ticker both derive from this so their timing
 * agrees.
 */
object LoopSchedule {

    const val KIND_INTERVAL = "interval"
    const val KIND_WEEKLY = "weekly"

    /** AlarmManager is inexact below ~15 min on modern Android, and a
     *  tighter cadence just burns the user's LLM budget. Floor every
     *  interval here so storage, scheduling, and the UI agree. */
    const val MIN_INTERVAL_MINUTES = 15

    /** Interval choices the UI offers: 15m, 30m, 1h, 3h, 6h, 12h, daily. */
    val PRESET_MINUTES = listOf(15, 30, 60, 180, 360, 720, 1440)

    fun dayBit(day: DayOfWeek): Int = 1 shl day.ordinal

    /** Weekdays for the editor's day picker, Sunday-first, each paired
     *  with its [LoopEntity.daysMask] bit (`1 shl DayOfWeek.ordinal`). */
    val WEEKDAYS: List<Pair<String, Int>> = listOf(
        "Sun" to dayBit(DayOfWeek.SUNDAY),
        "Mon" to dayBit(DayOfWeek.MONDAY),
        "Tue" to dayBit(DayOfWeek.TUESDAY),
        "Wed" to dayBit(DayOfWeek.WEDNESDAY),
        "Thu" to dayBit(DayOfWeek.THURSDAY),
        "Fri" to dayBit(DayOfWeek.FRIDAY),
        "Sat" to dayBit(DayOfWeek.SATURDAY),
    )

    // ── interval ────────────────────────────────────────────────────

    /** Next epoch-ms an interval loop should fire: one (floored) interval
     *  after its last run. May be in the past for an overdue loop. */
    fun nextFireMs(lastRunMs: Long, intervalMinutes: Int): Long =
        lastRunMs + intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES) * 60_000L

    fun isDue(now: Long, lastRunMs: Long, intervalMinutes: Int): Boolean =
        now >= nextFireMs(lastRunMs, intervalMinutes)

    // ── unified (interval or weekly) ────────────────────────────────

    /** Next epoch-ms [loop] should fire, honoring its schedule kind.
     *  May be in the past for an overdue loop — callers arm the alarm
     *  with it directly so it fires promptly on wake. */
    fun nextFireMs(loop: LoopEntity, zone: TimeZone): Long =
        if (loop.scheduleKind == KIND_WEEKLY) {
            nextWeeklyFireMs(loop.lastRunAt, loop.atMinuteOfDay, loop.daysMask, zone)
        } else {
            nextFireMs(loop.lastRunAt, loop.intervalMinutes)
        }

    /** True once [now] has reached [loop]'s next fire time. */
    fun isDue(now: Long, loop: LoopEntity, zone: TimeZone): Boolean =
        now >= nextFireMs(loop, zone)

    /**
     * First epoch-ms strictly after [afterMs] whose local (weekday, time)
     * matches [daysMask] at [atMinuteOfDay]. Scans day-by-day so DST is
     * handled by kotlinx-datetime's `toInstant` (gaps resolve forward,
     * ambiguous fall-back times resolve to the earlier offset).
     */
    fun nextWeeklyFireMs(afterMs: Long, atMinuteOfDay: Int, daysMask: Int, zone: TimeZone): Long {
        val after = Instant.fromEpochMilliseconds(afterMs)
        val time = LocalTime(atMinuteOfDay / 60, atMinuteOfDay % 60)
        val startDate = after.toLocalDateTime(zone).date
        // At most 8 days to reach the next matching weekday+time strictly
        // after `after` (7 for a single far day, +1 for same-day-already-past).
        for (i in 0..7) {
            val date = startDate.plus(i, DateTimeUnit.DAY)
            if (dayActive(date.dayOfWeek, daysMask)) {
                val candidate = LocalDateTime(date, time).toInstant(zone)
                if (candidate > after) return candidate.toEpochMilliseconds()
            }
        }
        // Unreachable for any schedule (daysMask == 0 matches every day);
        // guard so a corrupt row can't loop forever.
        return afterMs + 7 * 24 * 60 * 60_000L
    }

    private fun dayActive(day: DayOfWeek, daysMask: Int): Boolean =
        daysMask == 0 || (daysMask and dayBit(day)) != 0
}
