package io.nisfeb.talon.ui

/**
 * One failure-backoff policy for the weather fetch loops (the common
 * shell's and Android's): a failure waits 2^n minutes, capped at 30,
 * so an offline stretch costs a handful of attempts instead of one a
 * minute forever. In-memory — the Android widget can't use this (its
 * process dies between ticks, so it persists a failure timestamp
 * instead), but the policy is the same shape.
 */
class FetchBackoff {
    private var failures = 0
    private var nextAttemptAt = 0L

    /** True when a fetch may be tried. */
    fun ready(now: Long): Boolean = now >= nextAttemptAt

    fun onSuccess() {
        failures = 0
        nextAttemptAt = 0L
    }

    fun onFailure(now: Long) {
        failures = (failures + 1).coerceAtMost(5)
        nextAttemptAt = now + delayMs(failures)
    }

    companion object {
        /** 2, 4, 8, 16, 32 minutes after the 1st-5th consecutive failure. */
        fun delayMs(failures: Int): Long = 60_000L * (1L shl failures.coerceIn(1, 5))
    }
}
