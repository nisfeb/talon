package io.nisfeb.talon.orrery

import androidx.compose.runtime.staticCompositionLocalOf
import io.nisfeb.talon.ui.parseIsoUtc
import io.nisfeb.talon.urbit.asText
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Live location with another ship, through orrery (version 88): the
 * owner's phone sends a fix a minute while a share runs, their ship
 * passes it to the other ship's orrery, and that ship keeps the latest
 * one only. Talon starts and stops shares and shows them; the ships do
 * the sending.
 */
data class LocationShares(
    /** Shares this ship runs, each with one ship. */
    val out: List<OutShare>,
    /** Ships sharing where their owner is with this one. */
    val inbound: List<InShare>,
    /**
     * Ships this orrery does not know that started a share (orrery 137):
     * nothing of theirs shows until the owner accepts.
     */
    val asks: List<String> = emptyList(),
) {
    fun outTo(ship: String): OutShare? = out.firstOrNull { it.ship == ship }
    fun from(ship: String): InShare? = inbound.firstOrNull { it.ship == ship }
    fun asking(ship: String): Boolean = ship in asks
}

data class OutShare(val ship: String, val untilMs: Long?, val home: Boolean, val exact: Boolean)

data class InShare(
    val ship: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    /** Metres, as the sender's phone gave it; 1000 for an area share. */
    val accuracyM: Double?,
    val atMs: Long?,
    val untilMs: Long?,
    val home: Boolean,
)

/** How long a share runs, as orrery's share route takes it. */
enum class ShareLength(val label: String, val hours: Int?) {
    Hour("For 1 hour", 1),
    EightHours("For 8 hours", 8),
    Home("Until I'm home", null),
}

/** A share with no end time ends on the sender's ship at home; one quiet this long is taken to be over, as orrery 137 does. */
const val SHARE_QUIET_MS = 24 * 60 * 60_000L

/**
 * The shares in orrery's location document: the file itself, with `in`
 * keyed by ship, or the GET /api/location answer, with `in` a list.
 * What has ended is left out: a time passed, or a share with no end time
 * whose last fix is a day old (its "stopped" may have been lost).
 */
fun locationShares(doc: JsonObject, nowMs: Long): LocationShares {
    fun ms(e: JsonElement?) = e.asText()?.takeIf { it.isNotBlank() }?.let(::parseIsoUtc)
    fun flag(e: JsonElement?) = (e as? JsonPrimitive)?.content == "true"
    val out = (doc["out"] as? JsonArray).orEmpty().mapNotNull { g ->
        g as? JsonObject ?: return@mapNotNull null
        val ship = g["ship"].asText()?.takeIf { it.startsWith("~") } ?: return@mapNotNull null
        val until = ms(g["until"])
        val home = flag(g["home"])
        if (if (until != null) until <= nowMs else !home) return@mapNotNull null
        OutShare(ship, until, home, flag(g["exact"]))
    }
    val entries: List<Pair<String?, JsonObject>> = when (val i = doc["in"]) {
        is JsonObject -> i.entries.mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to it } }
        is JsonArray -> i.mapNotNull { (it as? JsonObject)?.let { o -> null to o } }
        else -> emptyList()
    }
    val inbound = entries.mapNotNull { (key, v) ->
        val ship = (key ?: v["ship"].asText())?.takeIf { it.startsWith("~") } ?: return@mapNotNull null
        val lat = v["lat"].asText()?.toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: return@mapNotNull null
        val lon = v["lon"].asText()?.toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: return@mapNotNull null
        val until = ms(v["until"])
        val at = ms(v["at"])
        val live = if (until != null) until > nowMs else at != null && nowMs - at < SHARE_QUIET_MS
        if (!live) return@mapNotNull null
        InShare(ship, v["name"].asText().orEmpty(), lat, lon, v["acc"].asText()?.toDoubleOrNull(), at, until, flag(v["home"]))
    }
    // An ask holds a fix too, never shown; it lives as a share with no end time does.
    val asks = when (val a = doc["asks"]) {
        is JsonObject -> a.entries.mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to it } }
        is JsonArray -> a.mapNotNull { (it as? JsonObject)?.let { o -> o["ship"].asText() to o } }
        else -> emptyList()
    }.mapNotNull { (ship, v) ->
        ship?.takeIf { it.startsWith("~") && ms(v["at"])?.let { at -> nowMs - at < SHARE_QUIET_MS } == true }
    }
    return LocationShares(out, inbound, asks)
}

/** The body of POST /api/location/accept and /decline (orrery 137). */
fun askBody(ship: String): String = buildJsonObject { put("ship", ship) }.toString()

/** The body of POST /api/location/share, as orrery's +serve-location-share reads it. */
fun shareBody(ship: String, length: ShareLength, exact: Boolean): String = buildJsonObject {
    put("ship", ship)
    if (length.hours != null) put("hours", length.hours) else put("home", true)
    put("precision", if (exact) "exact" else "area")
}.toString()

/**
 * Until when the phone should send a fix a minute: the last share's end,
 * or, for one that ends only at home (which the ship sees, not the
 * phone), [recheckMs] from now, when the phone reads the shares again.
 * Null when no share runs.
 */
fun sendFastUntil(shares: LocationShares, nowMs: Long, recheckMs: Long = 15 * 60_000L): Long? =
    shares.out.maxOfOrNull { it.untilMs ?: (nowMs + recheckMs) }

/**
 * Whether a fix goes up now. While a share runs (or a trip), at most one
 * each [TRIP_GAP_MS]: iOS hands over a fix on every [TRIP_MOVE_M], and
 * at speed that is several a minute. Otherwise each fix the platform
 * wakes the phone for, which is already a move of hundreds of metres.
 * Android's LocationManager keeps the minute itself.
 */
fun fixDue(lastSentMs: Long?, nowMs: Long, fast: Boolean): Boolean =
    !fast || lastSentMs == null || nowMs - lastSentMs >= TRIP_GAP_MS

/** Live location as the screens reach it; [LocalLiveLocation] holds the signed-in ship's, or null where it has no orrery. */
interface LiveLocation {
    /** As last read, or null until then. */
    val shares: StateFlow<LocationShares?>

    /** Whether this device can send fixes, so offer to share. */
    val canShare: Boolean

    /** Read the shares again: cheap, a scry where the ship allows one. */
    suspend fun refresh()

    /**
     * Start sharing with each of [ships], one grant each (a group's members),
     * or replace the share there. Runs to the end on the repo's scope; the
     * first refusal stops it, and the shares made before it stand.
     */
    suspend fun share(ships: List<String>, length: ShareLength, exact: Boolean): Result<Unit>

    /** Stop sharing with each of [ships]. Runs to the end on the repo's scope. */
    suspend fun stop(ships: List<String>): Result<Unit>

    /** Take [ship]'s ask: orrery makes them a person here and shows where they are. On the repo's scope. */
    suspend fun accept(ship: String): Result<Unit>

    /** Turn [ship]'s ask down: orrery drops their fixes from now on. On the repo's scope. */
    suspend fun decline(ship: String): Result<Unit>
}

val LocalLiveLocation = staticCompositionLocalOf<LiveLocation?> { null }

/**
 * The most members a group share goes to: one grant, and a poke a
 * minute, per member.
 * ponytail: a share per member; a trunk "location room" fans out on the
 * group's own roster if groups get bigger than a family.
 */
const val GROUP_SHARE_MAX = 20
