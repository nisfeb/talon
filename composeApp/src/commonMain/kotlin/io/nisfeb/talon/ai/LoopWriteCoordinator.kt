package io.nisfeb.talon.ai

import io.nisfeb.talon.data.LoopEntity

/**
 * Cross-device gate for a scheduled write-loop fire. With several devices
 * logged into one ship and all running, each independently sees the same
 * loop come due (lastRunAt is device-local) and would run it — so a
 * write-authorized automation fires N times, poking the ship N times.
 *
 * [claim] is consulted ONLY on the scheduled path ([LoopRunner.runDue]) and
 * ONLY for write-authorized loops. It returns true on the one device that
 * should run this fire; the others get false and skip. The real impl is a
 * lease in the ship's %settings (see SettingsSyncImpl) with auto-failover.
 *
 * Read-only loops and the manual "Run now" button never go through here —
 * reads are harmless to duplicate and a manual run is an explicit per-device
 * action. The [Noop] default runs everywhere (no coordination), which is the
 * correct fallback for a host with no %settings channel: a write loop needs
 * the ship anyway, so an un-coordinated device that can't reach it can't
 * double-write regardless.
 */
interface LoopWriteCoordinator {
    /** @return true if THIS device should run [loop]'s scheduled write fire. */
    suspend fun claim(loop: LoopEntity): Boolean = true

    /** Whether the coordinator can reach the ship at all right now. False
     *  (e.g. no %settings channel on a headless cold start) is a different
     *  situation from losing a [claim] contest: NOBODY ran the fire, and
     *  the caller should surface that instead of silently skipping. */
    fun canCoordinate(): Boolean = true

    /** No coordination — run on every device. */
    companion object Noop : LoopWriteCoordinator
}

/** What a device should do with a lease it just read. */
internal enum class ClaimDecision { RUN, SKIP, CONTEST }

/**
 * Pure lease decision — unit-tested and shared by the real impl
 * (SettingsSyncImpl.claim), so the branch logic can't drift from its test.
 *
 * @param holder current (deviceId, claimedAt-ms) on the ship, or null if
 *   unclaimed. @param me this device's id. @param staleMs how long since
 *   [holder]'s claim counts as the holder having gone offline.
 */
internal fun decideClaim(
    holder: Pair<String, Long>?,
    me: String,
    now: Long,
    staleMs: Long,
): ClaimDecision = when {
    holder == null -> ClaimDecision.CONTEST          // unclaimed → contest
    holder.first == me -> ClaimDecision.RUN          // already mine → refresh + run
    now - holder.second > staleMs -> ClaimDecision.CONTEST // holder offline → contest
    else -> ClaimDecision.SKIP                       // fresh holder, not me → skip
}
