package io.nisfeb.talon.ai

import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

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
     * DST behavior comes from `ZonedDateTime.of`: spring-forward times
     * resolve to the next valid local instant; fall-back ambiguous
     * times resolve to the earlier offset.
     */
    fun nextFireMs(now: Instant, hourOfDay: Int, minuteOfDay: Int, zone: ZoneId): Long {
        val nowZdt = now.atZone(zone)
        val candidate = LocalDateTime.of(
            nowZdt.toLocalDate(), LocalTime.of(hourOfDay, minuteOfDay),
        ).atZone(zone)
        val fire = if (candidate.toInstant() > now) candidate
            else candidate.plusDays(1)
        return fire.toInstant().toEpochMilli()
    }
}
