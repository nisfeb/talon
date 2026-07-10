package io.nisfeb.talon.util

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime

/**
 * Multiplatform display formatters for epoch-millis timestamps —
 * replacements for the per-screen `SimpleDateFormat(...).format(Date(ms))`
 * that commonMain used to reach for (java.text is JVM-only).
 *
 * Month/weekday names are English-abbreviated. The app's UI is English,
 * so this matches what SimpleDateFormat(Locale.getDefault()) produced on
 * the overwhelming majority of installs, without a JVM dependency.
 * All conversions use the device's current system time zone, same as
 * SimpleDateFormat's default.
 */

private fun local(ms: Long): LocalDateTime =
    Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.currentSystemDefault())

private fun localIn(ms: Long, zone: TimeZone): LocalDateTime =
    Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)

private val MONTH_DAY_TIME = LocalDateTime.Format {
    monthName(MonthNames.ENGLISH_ABBREVIATED); char(' ')
    dayOfMonth(Padding.NONE); char(' ')
    hour(); char(':'); minute()
}
private val MONTH_DAY_YEAR = LocalDateTime.Format {
    monthName(MonthNames.ENGLISH_ABBREVIATED); char(' ')
    dayOfMonth(Padding.NONE); chars(", "); year()
}
private val MONTH_DAY = LocalDateTime.Format {
    monthName(MonthNames.ENGLISH_ABBREVIATED); char(' '); dayOfMonth(Padding.NONE)
}
private val TIME_24 = LocalDateTime.Format {
    hour(); char(':'); minute()
}
private val WEEKDAY_MONTH_DAY = LocalDateTime.Format {
    dayOfWeek(DayOfWeekNames.ENGLISH_ABBREVIATED); chars(", ")
    monthName(MonthNames.ENGLISH_ABBREVIATED); char(' '); dayOfMonth(Padding.NONE)
}
private val TIME_12 = LocalDateTime.Format {
    amPmHour(Padding.NONE); char(':'); minute(); char(' '); amPmMarker("AM", "PM")
}
private val WEEKDAY_SHORT = LocalDateTime.Format {
    dayOfWeek(DayOfWeekNames.ENGLISH_ABBREVIATED)
}

/** "MMM d HH:mm" — e.g. "Jul 10 13:26". */
fun formatMonthDayTime(ms: Long): String = MONTH_DAY_TIME.format(local(ms))

/** "MMM d, yyyy" — e.g. "Jul 10, 2026". */
fun formatMonthDayYear(ms: Long): String = MONTH_DAY_YEAR.format(local(ms))

/** "MMM d" — e.g. "Jul 10". */
fun formatMonthDay(ms: Long): String = MONTH_DAY.format(local(ms))

/** "HH:mm" — 24-hour, e.g. "13:26". */
fun formatTime24(ms: Long): String = TIME_24.format(local(ms))

/** "EEE, MMM d" — e.g. "Fri, Jul 10". */
fun formatWeekdayMonthDay(ms: Long): String = WEEKDAY_MONTH_DAY.format(local(ms))

/** "h:mm a" — 12-hour, e.g. "1:26 PM". */
fun formatTime12(ms: Long): String = TIME_12.format(local(ms))

/** "h:mm a" in a specific zone — for cross-timezone rendering (/tz). */
fun formatTime12(ms: Long, zone: TimeZone): String = TIME_12.format(localIn(ms, zone))

/** "EEE" weekday in a specific zone, e.g. "Mon". */
fun formatWeekdayShort(ms: Long, zone: TimeZone): String = WEEKDAY_SHORT.format(localIn(ms, zone))
