package io.nisfeb.talon.relay

import kotlin.test.Test
import kotlin.test.assertEquals

class SuppressionTest {

    private val cfg = SuppressionConfig(
        firstConnectWarmupMs = 60_000L,
        reconnectWarmupMs = 5_000L,
        freshnessMaxAgeMs = 5L * 60_000L,
    )

    private val now = 1_700_000_000_000L

    @Test
    fun `first connect within warmup is suppressed as WARMUP`() {
        // Brand-new (ship, device) pair, ~30s into the 60s window —
        // every event suppressed regardless of how recent it is.
        val result = decideSuppress(
            nowMs = now,
            connStartMs = now - 30_000L,
            isFirstConnect = true,
            eventTimeMs = now,
            config = cfg,
        )
        assertEquals(SuppressReason.WARMUP, result)
    }

    @Test
    fun `first connect past warmup with fresh event pushes`() {
        // 61s after first-connect start, fresh event — push.
        val result = decideSuppress(
            nowMs = now,
            connStartMs = now - 61_000L,
            isFirstConnect = true,
            eventTimeMs = now,
            config = cfg,
        )
        assertEquals(SuppressReason.NONE, result)
    }

    @Test
    fun `reconnect uses the shorter warmup window`() {
        // 4s in — still in warmup for a reconnect (5s cap).
        val warm = decideSuppress(
            nowMs = now,
            connStartMs = now - 4_000L,
            isFirstConnect = false,
            eventTimeMs = now,
            config = cfg,
        )
        assertEquals(SuppressReason.WARMUP, warm)

        // 6s in — past the reconnect warmup, fresh event pushes.
        val ok = decideSuppress(
            nowMs = now,
            connStartMs = now - 6_000L,
            isFirstConnect = false,
            eventTimeMs = now,
            config = cfg,
        )
        assertEquals(SuppressReason.NONE, ok)
    }

    @Test
    fun `stale event past warmup is suppressed as STALE`() {
        // Connection has been up long enough; the event itself is
        // 10 minutes old — backlog from a ship-side reboot mid-stream.
        val result = decideSuppress(
            nowMs = now,
            connStartMs = now - 600_000L,
            isFirstConnect = false,
            eventTimeMs = now - 10L * 60_000L,
            config = cfg,
        )
        assertEquals(SuppressReason.STALE, result)
    }

    @Test
    fun `fresh event past warmup pushes`() {
        // Event is 1 minute old — within the 5 min freshness window.
        val result = decideSuppress(
            nowMs = now,
            connStartMs = now - 600_000L,
            isFirstConnect = false,
            eventTimeMs = now - 60_000L,
            config = cfg,
        )
        assertEquals(SuppressReason.NONE, result)
    }

    @Test
    fun `null eventTimeMs skips the freshness check`() {
        // postIdToMs returned null (malformed id) — be permissive:
        // we already trust the warmup mechanism to catch backlog
        // dumps; falling back on warmup alone is fine here.
        val result = decideSuppress(
            nowMs = now,
            connStartMs = now - 600_000L,
            isFirstConnect = false,
            eventTimeMs = null,
            config = cfg,
        )
        assertEquals(SuppressReason.NONE, result)
    }

    @Test
    fun `warmup takes precedence over freshness`() {
        // In warmup AND event is stale — should report WARMUP (the
        // primary reason). Either way the action is "suppress".
        val result = decideSuppress(
            nowMs = now,
            connStartMs = now - 1_000L,
            isFirstConnect = true,
            eventTimeMs = now - 600_000L,
            config = cfg,
        )
        assertEquals(SuppressReason.WARMUP, result)
    }
}
