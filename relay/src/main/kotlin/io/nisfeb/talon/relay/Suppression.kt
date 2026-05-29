package io.nisfeb.talon.relay

/**
 * Pure decision logic for "should we suppress this push?". Two
 * mechanisms layered together:
 *
 * 1. **Cold-start warmup** — for the first
 *    [SuppressionConfig.firstConnectWarmupMs] of a brand-new
 *    `(ship, device)` connection (no `last_event` cursor yet), every
 *    incoming event is suppressed. This catches the "first registration
 *    backlog" — Tlon's `%activity` agent emits every existing
 *    notify-worthy event on the initial subscribe-fact, and without
 *    this cap a new user could wake up to dozens or hundreds of
 *    notifications for things they've already read in the app.
 *
 *    On every subsequent SSE connection (reconnects), a shorter
 *    [SuppressionConfig.reconnectWarmupMs] applies — enough to swallow
 *    a reconnect replay burst while keeping steady-state latency low.
 *
 * 2. **Freshness filter** — independent of warmup, an event whose own
 *    timestamp is older than [SuppressionConfig.freshnessMaxAgeMs] is
 *    suppressed. Catches the case where the agent emits a backlog
 *    mid-stream (after a ship reboot, say) when warmup is no longer
 *    active. The user's client will surface those events when they
 *    next open the app; we just don't want them on the lock screen.
 *
 * Events suppressed via either mechanism should still advance the
 * cursor — the caller does that — so the next live event is treated
 * as "fresh after we caught up".
 */
internal data class SuppressionConfig(
    val firstConnectWarmupMs: Long = 60_000L,
    val reconnectWarmupMs: Long = 5_000L,
    val freshnessMaxAgeMs: Long = 5L * 60_000L,
)

internal enum class SuppressReason {
    NONE,
    WARMUP,
    STALE,
}

internal fun decideSuppress(
    nowMs: Long,
    connStartMs: Long,
    isFirstConnect: Boolean,
    /** unix-ms timestamp of the event itself, or null when we couldn't
     *  extract one from the post id (defensive — when in doubt, defer
     *  to the warmup decision only). */
    eventTimeMs: Long?,
    config: SuppressionConfig = SuppressionConfig(),
): SuppressReason {
    val connAgeMs = nowMs - connStartMs
    val warmupCap = if (isFirstConnect) config.firstConnectWarmupMs
        else config.reconnectWarmupMs
    if (connAgeMs < warmupCap) return SuppressReason.WARMUP
    if (eventTimeMs != null && (nowMs - eventTimeMs) > config.freshnessMaxAgeMs) {
        return SuppressReason.STALE
    }
    return SuppressReason.NONE
}
