package io.nisfeb.talon.ui

/**
 * Where this device stands with its ship, as the chat list shows it. A
 * Windows user's Talon stopped syncing for three days and said nothing
 * (2026-10-29); this is what says it.
 *
 * What it can know is what came down the ship's event stream: eyre sends
 * something at least every ~25 seconds, news or not, so bytes on it mean
 * the ship is there. It cannot tell a ship that has nothing new from one
 * that stopped sending this device its news while the stream stays up.
 */
sealed interface SyncStatus {
    /** The ship refused this login: nothing comes until it is signed in again. */
    data object SignedOut : SyncStatus

    /** Not heard from it yet this run, and not for long. */
    data object Connecting : SyncStatus

    /**
     * Heard from it [heardAgoMs] ago. [syncing] while the first read of the
     * chats is under way, [pending] writes the ship has not taken yet.
     */
    data class Connected(val heardAgoMs: Long, val syncing: Boolean, val pending: Int) : SyncStatus

    /** Not heard from it since [sinceMs] (when this run began trying, if never); [pending] writes wait. */
    data class Unreachable(val sinceMs: Long, val pending: Int) : SyncStatus
}

/** How long the chat list waits before it says something is wrong. */
data class SyncThresholds(
    /** Heard within this, it is connected: three of eyre's heartbeats, so a reconnect's few seconds never show. */
    val heardRecentlyMs: Long = 75_000L,
    /** Never heard yet this run, connecting for this long before it is unreachable. */
    val connectGraceMs: Long = 20_000L,
)

/**
 * [lastHeardMs]: when anything last came from the ship on its event
 * stream, heartbeats included, or null for not yet this run;
 * [trySinceMs]: when this run began trying.
 */
fun syncStatus(
    signedOut: Boolean,
    lastHeardMs: Long?,
    trySinceMs: Long,
    nowMs: Long,
    syncing: Boolean,
    pending: Int,
    thresholds: SyncThresholds = SyncThresholds(),
): SyncStatus = when {
    signedOut -> SyncStatus.SignedOut
    lastHeardMs != null && nowMs - lastHeardMs <= thresholds.heardRecentlyMs ->
        SyncStatus.Connected(nowMs - lastHeardMs, syncing, pending)
    lastHeardMs == null && nowMs - trySinceMs <= thresholds.connectGraceMs -> SyncStatus.Connecting
    else -> SyncStatus.Unreachable(lastHeardMs ?: trySinceMs, pending)
}

/** The status in a line, for the header's tip and the banner: "Can't reach ~zod since 2:02 PM". */
fun syncHeadline(status: SyncStatus, ship: String, nowMs: Long, at: (Long) -> String = { sinceLabel(it, nowMs) }): String = when (status) {
    SyncStatus.SignedOut -> "Signed out of $ship"
    SyncStatus.Connecting -> "Connecting to $ship…"
    is SyncStatus.Connected -> when {
        status.syncing -> "Syncing with $ship…"
        status.pending > 0 -> "Sending ${status.pending} to $ship…"
        else -> "Connected to $ship"
    }
    is SyncStatus.Unreachable -> "Can't reach $ship since ${at(status.sinceMs)}"
}

/** What follows the line: how fresh the connection is, or what waits and what to do. */
fun syncDetail(status: SyncStatus): String = when (status) {
    SyncStatus.SignedOut -> "New messages wait until you sign in again."
    SyncStatus.Connecting -> ""
    is SyncStatus.Connected -> "Heard from it ${agoLabel(status.heardAgoMs)}."
    is SyncStatus.Unreachable -> waiting(status.pending) + "Talon keeps trying."
}

private fun waiting(n: Int) = when (n) {
    0 -> ""
    1 -> "1 waiting to send. "
    else -> "$n waiting to send. "
}

/** "just now", "40 s ago", "3 min ago". */
fun agoLabel(ms: Long): String = when {
    ms < 5_000L -> "just now"
    ms < 60_000L -> "${ms / 1_000L} s ago"
    else -> "${ms / 60_000L} min ago"
}

/** "2:02 PM" today, "Oct 26 2:02 PM" before it. */
fun sinceLabel(ms: Long, nowMs: Long): String =
    if (io.nisfeb.talon.util.formatMonthDay(ms) == io.nisfeb.talon.util.formatMonthDay(nowMs)) io.nisfeb.talon.util.formatClock(ms)
    else io.nisfeb.talon.util.formatMonthDayClock(ms)
