package io.nisfeb.talon.orrery

import io.ktor.client.HttpClient
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.OrrerySentEntity
import io.nisfeb.talon.util.Log
import io.nisfeb.talon.util.createAppHttpClient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import io.nisfeb.talon.urbit.asText

/**
 * Where the owner is, from the phone, as the schema asks for it: a
 * place the ship knows as `{"ref": "place/..."}`, else a short place
 * name, and null once they have left and the new place is unknown.
 * Never the coordinates: those stay on the phone.
 */
data class LocationFix(val lat: Double, val lon: Double, val accuracyM: Double, val atMs: Long)

/** A place body with a position the ship has for it. */
data class GeoPlace(val id: String, val lat: Double, val lon: Double)

/** The places in the state view with a `geo` the ship can place: "lat,lon", or an object with lat and lon (or lng). */
fun geoPlaces(state: JsonObject): List<GeoPlace> = OrreryText.bodies(state).mapNotNull { b ->
    val id = b["id"].asText()?.takeIf { it.startsWith("place/") } ?: return@mapNotNull null
    val (lat, lon) = when (val g = OrreryText.value(b, "geo")) {
        is JsonPrimitive -> g.contentOrNull?.let { NUMBER.findAll(it).map { m -> m.value.toDouble() }.toList() }
            ?.takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: return@mapNotNull null
        is JsonObject -> {
            fun n(k: String) = (g[k] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
            (n("lat") ?: return@mapNotNull null) to (n("lon") ?: n("lng") ?: return@mapNotNull null)
        }
        else -> return@mapNotNull null
    }
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
    GeoPlace(id, lat, lon)
}

private val NUMBER = Regex("-?\\d+(\\.\\d+)?")

/** Metres between two points on the earth, near enough for "is this the same place". */
fun metresBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    fun rad(d: Double) = d * kotlin.math.PI / 180
    val a = sin(rad(lat2 - lat1) / 2).let { it * it } + cos(rad(lat1)) * cos(rad(lat2)) * sin(rad(lon2 - lon1) / 2).let { it * it }
    return 2 * 6_371_000 * asin(sqrt(a))
}

/**
 * What to say about where the owner is, or nothing. The nearest known
 * place within [MATCH_M] (or the fix's own accuracy, up to [MAX_MATCH_M])
 * is where they are; else the place name the phone looked up; else, if
 * the last thing said named somewhere, null, since they have left it.
 */
fun locationValue(fix: LocationFix, places: List<GeoPlace>, name: String?, last: JsonElement?): JsonElement? {
    val reach = fix.accuracyM.coerceIn(MATCH_M, MAX_MATCH_M)
    val here = places.map { it to metresBetween(fix.lat, fix.lon, it.lat, it.lon) }
        .filter { it.second <= reach }
        .minByOrNull { it.second }?.first
    return when {
        here != null -> buildJsonObject { put("ref", here.id) }
        !name.isNullOrBlank() -> JsonPrimitive(name.trim().take(80))
        last != null && last !is JsonNull -> JsonNull
        else -> null
    }
}

const val MATCH_M = 150.0
const val MAX_MATCH_M = 500.0

/**
 * Send one fix to orrery under this install's key, when it says
 * something new: one observation on the owner's body, source
 * `device`. The last value sent is kept per ship, so the same place
 * twice is said once, and a phone that stays put costs the ship
 * nothing. [name] is the phone's own reverse lookup, where it has one.
 */
suspend fun sendLocation(
    http: HttpClient,
    db: AppDatabase,
    url: String,
    ship: String,
    fix: LocationFix,
    name: String?,
    /** The key's client, which carries no cookie. */
    bare: HttpClient = keyClient,
): Result<Unit> = runCatching {
    val token = db.orreryAccounts().get(ship)?.token ?: return Result.success(Unit)
    val api = OrreryApi(http, bare, url)
    val state = api.stateJson(token)
    val sent = db.orrerySent()
    val last = sent.get(ship, LAST_KEY)?.value?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) }.getOrNull() }
    val value = locationValue(fix, geoPlaces(state), name, last) ?: return Result.success(Unit)
    if (value == last) return Result.success(Unit)
    val me = OrreryText.me(state)
    val obs = Obs(me, "location", value, fix.atMs, conf = if (value is JsonObject) 90 else 80, sourceKind = "device", sourceId = "location")
    val answer = api.observe(batches(Facts(emptyList(), listOf(obs))).single(), token)
    answer.refused.firstOrNull()?.let { error(it.error ?: "refused") }
    sent.put(OrrerySentEntity(ship, LAST_KEY, value.toString(), fix.atMs))
    Log.i("OrreryLocation", "location sent: ${if (value is JsonObject) value["ref"] else value}")
}

private const val LAST_KEY = "location:last"

/**
 * The key's client, made once. It used to be made per fix and never
 * closed, so every move left a connection pool and its threads behind.
 */
private val keyClient: HttpClient by lazy { createAppHttpClient() }
