package io.nisfeb.talon.ui

import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable

/**
 * Who can reach the owner, and when (sneagan, 2026-11-08: "control over
 * who reaches you"). The ship lets anyone open a DM, as a request, so
 * these decide what alerts, on every device: they travel as one ui-prefs
 * entry ([io.nisfeb.talon.urbit.SettingsSyncImpl.ENTRY_REACH]).
 *
 * ponytail: one entry for the three, last write wins for the whole; split
 * it if two devices editing at once ever loses a change.
 */
@Serializable
data class ReachSettings(
    val whoCanMessage: WhoCanMessage = WhoCanMessage.Anyone,
    /** Ships whose messages alert even in quiet hours. */
    val favourites: Set<String> = emptySet(),
    val quiet: QuietHours = QuietHours(),
)

@Serializable
enum class WhoCanMessage {
    /** A stranger's DM is a request, and the request alerts. */
    Anyone,

    /** Only people in the contact book alert; anyone else's request waits silently. */
    Added,
}

/**
 * A daily stretch when messages do not alert, but a favourite's. [fromMin]
 * and [toMin] are minutes after midnight in [zone]; one that ends before
 * it starts runs over midnight. [zone] is the device's where it was set,
 * so the relay, which has no clock of the owner's, can tell the time.
 * [offsetMin] is that zone's minutes from UTC, for the ship's own pushes
 * (%trunk): a ship has no zone table. Null from a build before it, and
 * the ship then holds nothing.
 */
@Serializable
data class QuietHours(
    val on: Boolean = false,
    val fromMin: Int = 22 * 60,
    val toMin: Int = 7 * 60,
    val zone: String = "",
    val offsetMin: Int? = null,
)

/** This device's zone and its minutes from UTC now, as quiet hours carry them. */
fun deviceClock(atMs: Long = io.nisfeb.talon.util.nowMs()): Pair<String, Int> {
    val zone = TimeZone.currentSystemDefault()
    return zone.id to zone.offsetAt(kotlin.time.Instant.fromEpochMilliseconds(atMs)).totalSeconds / 60
}

/** [this] as set on this device: its zone and offset go with it. */
fun QuietHours.onThisClock(clock: Pair<String, Int> = deviceClock()): QuietHours = copy(zone = clock.first, offsetMin = clock.second)

/**
 * [r] with its quiet hours' offset put right for [clock], or null when
 * nothing needs writing. Only hours that are on and of this device's own
 * zone (or of none): summer time moved the offset, or a build before it
 * wrote none. Another zone's are left to the device in it, or two devices
 * in two zones would write over each other at every start.
 */
fun quietClockDue(r: ReachSettings, clock: Pair<String, Int>): ReachSettings? {
    val q = r.quiet
    if (!q.on || (q.zone.isNotEmpty() && q.zone != clock.first)) return null
    val right = q.onThisClock(clock)
    return if (right == q) null else r.copy(quiet = right)
}

/** Keeps the quiet hours' offset right while the app runs ([quietClockDue]): at start, and as a setting arrives from another device. */
suspend fun keepQuietClock(ui: UiSettings) {
    ui.reach.collect { r -> quietClockDue(r, deviceClock())?.let(ui::setReach) }
}

private val reachJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun ReachSettings.toJson(): String = reachJson.encodeToString(ReachSettings.serializer(), this)

/** Null for nothing stored or what this build cannot read: the defaults, then. */
fun reachFromJson(s: String?): ReachSettings? =
    s?.takeIf { it.isNotBlank() }?.let { runCatching { reachJson.decodeFromString(ReachSettings.serializer(), it) }.getOrNull() }

/** Whether [minuteOfDay] falls in [q]. A stretch from a time to the same time is none. */
fun quietAt(q: QuietHours, minuteOfDay: Int): Boolean = q.on && when {
    q.fromMin == q.toMin -> false
    q.fromMin < q.toMin -> minuteOfDay >= q.fromMin && minuteOfDay < q.toMin
    else -> minuteOfDay >= q.fromMin || minuteOfDay < q.toMin
}

/** Whether a message from [author] alerts at [minuteOfDay]: a favourite always does. */
fun messageReaches(r: ReachSettings, author: String, minuteOfDay: Int): Boolean =
    author in r.favourites || !quietAt(r.quiet, minuteOfDay)

/**
 * Whether a push that names only the chat ([whom]), not who wrote, alerts:
 * in quiet hours only a favourite's DM, since nothing else can be told apart.
 */
fun hintReaches(r: ReachSettings, whom: String, minuteOfDay: Int): Boolean =
    whom in r.favourites || !quietAt(r.quiet, minuteOfDay)

/**
 * Whether [ship]'s DM request alerts: with [WhoCanMessage.Added] only a
 * contact's ([inBook]) or a favourite's, and then as a message would.
 */
fun requestReaches(r: ReachSettings, ship: String, inBook: Boolean, minuteOfDay: Int): Boolean =
    (r.whoCanMessage == WhoCanMessage.Anyone || inBook || ship in r.favourites) && messageReaches(r, ship, minuteOfDay)

/** Minutes after local midnight now, as [quietAt] reads them. */
fun localMinuteOfDay(atMs: Long = io.nisfeb.talon.util.nowMs()): Int =
    kotlin.time.Instant.fromEpochMilliseconds(atMs).toLocalDateTime(TimeZone.currentSystemDefault())
        .let { it.hour * 60 + it.minute }

/** "10:00 PM", for a minute after midnight. */
fun minuteLabel(min: Int): String {
    val h = min / 60
    val m = min % 60
    val h12 = if (h % 12 == 0) 12 else h % 12
    return "$h12:${m.toString().padStart(2, '0')} ${if (h < 12) "AM" else "PM"}"
}
