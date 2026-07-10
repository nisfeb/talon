package io.nisfeb.talon.ai

/**
 * Pure scheduling math for user loops. Interval-based (vs the daily
 * digest's time-of-day), so it only needs the last run and the interval.
 * No side effects — same inputs, same answer. The Android alarm scheduler
 * and the desktop ticker both derive from this so their timing agrees.
 */
object LoopSchedule {

    /** AlarmManager is inexact below ~15 min on modern Android, and a
     *  tighter cadence just burns the user's LLM budget. Floor every
     *  interval here so storage, scheduling, and the UI agree. */
    const val MIN_INTERVAL_MINUTES = 15

    /** Interval choices the UI offers: 15m, 30m, 1h, 3h, 6h, 12h, daily. */
    val PRESET_MINUTES = listOf(15, 30, 60, 180, 360, 720, 1440)

    /** Next epoch-ms a loop should fire: one (floored) interval after its
     *  last run. May be in the past for an overdue loop — callers arm the
     *  alarm with it directly so AlarmManager fires it promptly on wake. */
    fun nextFireMs(lastRunMs: Long, intervalMinutes: Int): Long =
        lastRunMs + intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES) * 60_000L

    /** True once [now] has reached the next fire time. The desktop ticker
     *  polls this; the alarm path uses it as a clock-skew guard. */
    fun isDue(now: Long, lastRunMs: Long, intervalMinutes: Int): Boolean =
        now >= nextFireMs(lastRunMs, intervalMinutes)
}
