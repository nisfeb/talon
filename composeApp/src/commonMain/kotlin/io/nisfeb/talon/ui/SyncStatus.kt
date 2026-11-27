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
    /**
     * Heard within this, it is connected. Five minutes: silence is not a
     * ship out of reach. A busy ship sends no heartbeat while it works,
     * the stream is given 90 s before it is opened again, and opening it
     * on a slow ship takes its own time. At 75 s the red line came up
     * before the first reconnect was tried, of a ship that was there
     * (sneagan, 2026-11-13: "way too sensitive", "pops up all the time").
     */
    val heardRecentlyMs: Long = 5 * 60_000L,
    /** Nothing heard since it began trying (this run, or back at the app), connecting for this long before it is unreachable. */
    val connectGraceMs: Long = 20_000L,
    /** Nothing heard for this long, and a notification says so outside the app ([SilenceAlert]). */
    val alertAfterMs: Long = 30 * 60_000L,
)

/** What [SilenceAlert] does on a tick. */
sealed interface SilenceStep {
    /** Say it: nothing from the ship since [sinceMs]. */
    data class Post(val sinceMs: Long) : SilenceStep
    data object Clear : SilenceStep
    data object None : SilenceStep
}

/**
 * The notification for a ship that has said nothing for [alertAfterMs]
 * (sneagan, 2026-11-27: thirty minutes). Posted once an outage, and taken
 * back only when something comes from the ship again, or the repo stops
 * listening. Not on the status going to Connecting: it does that for a
 * moment each time the app comes to the front, mid-outage.
 *
 * Only time this run watched counts. A tick later than [maxGapMs] (a
 * laptop asleep, a phone frozen or dozing, iOS behind), no network, or a
 * repo not listening starts the count again: the last heard is from
 * before, and nothing was trying meanwhile. Two complaints were of a
 * "can't reach" said of a ship that was there.
 */
class SilenceAlert(private val alertAfterMs: Long, private val maxGapMs: Long) {
    private var lastTickMs: Long? = null
    private var watchingSinceMs = 0L
    private var postedAtHeard: Long? = null

    /**
     * [heardMs]: the repo's last heard, null for nothing yet this run.
     * [listening]: started and signed in. [online]: the device has a network.
     */
    fun tick(nowMs: Long, heardMs: Long?, listening: Boolean, online: Boolean = true): SilenceStep {
        val late = lastTickMs.let { it == null || nowMs - it > maxGapMs }
        lastTickMs = nowMs
        if (late || !online || !listening) watchingSinceMs = nowMs
        val heard = heardMs ?: 0L
        postedAtHeard?.let { at ->
            if (listening && heard <= at) return SilenceStep.None
            postedAtHeard = null
            return SilenceStep.Clear
        }
        val since = maxOf(heard, watchingSinceMs)
        if (!listening || nowMs - since < alertAfterMs) return SilenceStep.None
        postedAtHeard = heard
        return SilenceStep.Post(heardMs ?: since)
    }
}

/**
 * [lastHeardMs]: when anything last came from the ship on its event
 * stream, heartbeats included, or null for not yet this run;
 * [trySinceMs]: when this run began trying, or the app last came back to
 * the front. Nothing heard since then is a reconnect under way, not a
 * ship out of reach, until [SyncThresholds.connectGraceMs] has passed:
 * behind, the OS cuts the stream, and the last heard is from before.
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
    (lastHeardMs == null || lastHeardMs < trySinceMs) && nowMs - trySinceMs <= thresholds.connectGraceMs -> SyncStatus.Connecting
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
    is SyncStatus.Unreachable -> waiting(status.pending) + "Groundwire keeps trying."
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
