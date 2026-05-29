package io.nisfeb.talon.ui

import kotlin.math.abs

/**
 * Short human-friendly "Nm ago" / "Nh ago" formatter for in-row
 * timestamps (thread indicator, etc). Buckets match what chat clients
 * generally use:
 *
 *  - <60s        → "just now"
 *  - <60m        → "Nm ago"
 *  - <24h        → "Nh ago"
 *  - <7d         → "Nd ago"
 *  - otherwise   → "Nw ago" (capped at "52w ago"; older we just render
 *                 the same since exact week count past a year isn't
 *                 informative in chat context)
 *
 * Future-dated input (clock skew, edits dated forward) clamps to "just
 * now" so we never render "in 3h".
 */
fun shortRelativeTime(thenMs: Long, nowMs: Long): String {
    val deltaMs = nowMs - thenMs
    if (deltaMs <= 0L) return "just now"
    val secs = deltaMs / 1000L
    if (secs < 60L) return "just now"
    val mins = secs / 60L
    if (mins < 60L) return "${mins}m ago"
    val hours = mins / 60L
    if (hours < 24L) return "${hours}h ago"
    val days = hours / 24L
    if (days < 7L) return "${days}d ago"
    val weeks = (days / 7L).coerceAtMost(52L)
    return "${weeks}w ago"
}

/**
 * Public for tests so they don't need to recompute the bucket
 * boundaries by hand.
 */
internal fun secondsBetween(thenMs: Long, nowMs: Long): Long =
    abs(nowMs - thenMs) / 1000L
