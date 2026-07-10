package io.nisfeb.talon.ai

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Pure scheduling math for the daily digest alarm. See spec §Schedule.
 *
 * No side effects — given the inputs it always returns the same fire
 * time. The receiver / TalonApplication call this whenever they need
 * to (re)arm the alarm.
 */
object DailyDigestSchedule {

    /**
     * Next epoch-ms at which the daily digest should fire.
     *
     * If today's `(hourOfDay, minuteOfDay)` in [zone] is strictly after
     * [now], that's the answer. Otherwise add a day. Equal-to-now counts
     * as past so we don't fire twice on a re-arm at exactly fire time.
     *
     * DST behavior comes from kotlinx-datetime's `toInstant`: spring-
     * forward gaps resolve forward to the next valid instant; fall-back
     * ambiguous times resolve to the earlier offset.
     */
    fun nextFireMs(now: Instant, hourOfDay: Int, minuteOfDay: Int, zone: TimeZone): Long {
        val today = now.toLocalDateTime(zone).date
        val time = LocalTime(hourOfDay, minuteOfDay)
        val candidate = LocalDateTime(today, time).toInstant(zone)
        val fire = if (candidate > now) candidate
            else LocalDateTime(today.plus(1, DateTimeUnit.DAY), time).toInstant(zone)
        return fire.toEpochMilliseconds()
    }
}
