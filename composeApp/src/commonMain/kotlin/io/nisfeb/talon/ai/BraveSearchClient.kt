package io.nisfeb.talon.ai

import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.nisfeb.talon.util.createAppHttpClient
import io.nisfeb.talon.util.ioDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Brave Search client backing the assistant's `web_search` tool.
 *
 * Reads the live [AiSettings.Config] on every call (via [settings]) so a key
 * pasted — or the toggle flipped — in Settings takes effect immediately,
 * without rebuilding the tool. Never throws: every failure (disabled, no key,
 * HTTP error, bad JSON) comes back as a plain-English string the model can
 * relay. Response parsing is the pure top-level [parseBraveResults] so it's
 * unit-tested without a live API, mirroring AgentClient's build/parse split.
 */
/**
 * Where web search goes and the key it sends: the Armillary vendor's
 * search, which forwards to Brave on the vendor's key and takes this
 * ship's Armillary key in Brave's own header. Null where it offers none.
 */
fun AiSettings.Config.braveSearch(): Pair<String, String>? {
    val row = profile().armillary() ?: return null
    val url = row.searchUrl?.takeIf { it.isNotBlank() } ?: return null
    return row.searchKey.takeIf { it.isNotBlank() }?.let { url to it }
}


class BraveSearchClient(
    /** First, so a call's trailing lambda is [settings]; a test's own. */
    private val http: io.ktor.client.HttpClient = createAppHttpClient(),
    private val settings: () -> AiSettings.Config,
) {

    suspend fun search(query: String, count: Int): String {
        val cfg = settings()
        if (!cfg.assistantOn()) return "Web search is part of the assistant, which is off in Settings."
        val (base, key) = cfg.braveSearch() ?: return "No Brave Search API key is set in Settings."
        val q = query.trim()
        if (q.isBlank()) return "Error: query is required."
        val n = count.coerceIn(1, 20)

        return withContext(ioDispatcher) {
            runCatching {
                val resp = http.get("$base/res/v1/web/search") {
                    parameter("q", q)
                    parameter("count", n.toString())
                    header("X-Subscription-Token", key)
                    header("Accept", "application/json")
                    timeout { requestTimeoutMillis = 30_000 }
                }
                val body = resp.bodyAsText()
                if (!resp.status.isSuccess()) {
                    // 401/403 = bad/missing key; 429 = quota. Surface the
                    // code + a snippet so the user can tell which.
                    "Brave Search error ${resp.status.value}: ${body.take(200)}"
                } else {
                    parseBraveResults(body, n)
                }
            }.getOrElse { "Web search failed: ${it.message ?: it::class.simpleName}" }
        }
    }

    /**
     * The places matching [query] nearest to [near], nearest first: the
     * assistant's `find_places`. [near] is an address, a place's name or
     * "lat,lon"; a name or address is looked up first (in [area], "city
     * state country", where given), and the answer says what it was found
     * as, so a lookup that lands on a same-named place elsewhere shows.
     * Distances are straight-line, sorted here: the place search does not
     * say in what order it answers. Never throws.
     */
    suspend fun places(query: String, near: String, area: String?, count: Int): String {
        val cfg = settings()
        if (!cfg.assistantOn()) return "Place search is part of the assistant, which is off in Settings."
        val (base, key) = cfg.braveSearch() ?: return "Place search comes through your Armillary vendor's search, which it does not offer."
        val what = query.trim()
        val where = near.trim()
        if (what.isBlank()) return "Error: query is required."
        if (where.isBlank()) return "Error: near is required."
        val n = count.coerceIn(1, 10)
        return withContext(ioDispatcher) {
            runCatching {
                val anchor = latLonOf(where)?.let { (la, lo) -> FoundPlace(where, "", la, lo, "", "") } ?: run {
                    val (ok, body) = placeSearch(base, key) {
                        parameter("q", where)
                        area?.trim()?.takeIf { it.isNotEmpty() }?.let { parameter("location", it) }
                        parameter("count", "3")
                    }
                    if (!ok) return@runCatching body
                    parsePlaces(body).firstOrNull() ?: return@runCatching "Couldn't find \"$where\"${area?.takeIf { it.isNotBlank() }?.let { " in $it" }.orEmpty()} to search near."
                }
                val (ok, body) = placeSearch(base, key) {
                    parameter("q", what)
                    parameter("latitude", anchor.lat.toString())
                    parameter("longitude", anchor.lon.toString())
                    // A bias, not a cutoff: the nearest a little further out still comes.
                    parameter("radius", PLACE_RADIUS_M.toString())
                    parameter("count", "20")
                }
                if (!ok) return@runCatching body
                placesAnswer(what, anchor, parsePlaces(body), n)
            }.getOrElse { "Place search failed: ${it.message ?: it::class.simpleName}" }
        }
    }

    /** One place search: true and the body, or false and what to say. */
    private suspend fun placeSearch(
        base: String,
        key: String,
        params: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
    ): Pair<Boolean, String> {
        val resp = http.get("$base/res/v1/local/place_search") {
            params()
            header("X-Subscription-Token", key)
            header("Accept", "application/json")
            timeout { requestTimeoutMillis = 30_000 }
        }
        val body = resp.bodyAsText()
        return if (resp.status.isSuccess()) true to body else false to "Place search error ${resp.status.value}: ${body.take(200)}"
    }

    companion object {
        internal val JSON = Json { ignoreUnknownKeys = true }

        /** Where a place search looks first around its point, in metres. */
        const val PLACE_RADIUS_M = 30_000
    }
}

/** A place a place search found: where it is, and what to say of it. */
internal data class FoundPlace(
    val name: String,
    val address: String,
    val lat: Double,
    val lon: Double,
    val phone: String,
    /** Today's hours as the search gives them, "07:00-18:00", or "". */
    val hoursToday: String,
)

/** The places of a place-search body that have a position; none for an unreadable one. */
internal fun parsePlaces(json: String): List<FoundPlace> {
    val obj = runCatching { BraveSearchClient.JSON.parseToJsonElement(json).jsonObject }.getOrNull() ?: return emptyList()
    val results = (obj["results"] as? kotlinx.serialization.json.JsonArray) ?: return emptyList()
    fun JsonObject.str(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    return results.mapNotNull { el ->
        val r = el as? JsonObject ?: return@mapNotNull null
        val at = (r["coordinates"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toDoubleOrNull() }
        if (at == null || at.size < 2) return@mapNotNull null
        val pa = r["postal_address"] as? JsonObject
        val address = pa?.str("displayAddress")?.takeIf { it.isNotEmpty() }
            ?: listOf("streetAddress", "addressLocality", "addressRegion", "postalCode").mapNotNull { k -> pa?.str(k)?.takeIf { it.isNotEmpty() } }.joinToString(", ")
        val today = ((r["opening_hours"] as? JsonObject)?.get("current_day") as? kotlinx.serialization.json.JsonArray).orEmpty()
            .mapNotNull { d -> (d as? JsonObject)?.let { o -> o.str("opens").takeIf { it.isNotEmpty() }?.let { "$it-${o.str("closes")}" } } }
            .joinToString(", ")
        FoundPlace(r.str("title").ifEmpty { address }, address, at[0], at[1], (r["contact"] as? JsonObject)?.str("telephone").orEmpty(), today)
    }
}

/** "lat,lon" read as a position, or null for anything else. */
internal fun latLonOf(text: String): Pair<Double, Double>? {
    val parts = text.split(',').map { it.trim() }
    if (parts.size != 2) return null
    val la = parts[0].toDoubleOrNull() ?: return null
    val lo = parts[1].toDoubleOrNull() ?: return null
    return if (la in -90.0..90.0 && lo in -180.0..180.0) la to lo else null
}

/** The straight-line distance in metres between two positions (haversine). */
internal fun distanceM(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val r = 6_371_000.0
    fun rad(d: Double) = d * kotlin.math.PI / 180.0
    val dLat = rad(bLat - aLat)
    val dLon = rad(bLon - aLon)
    val h = kotlin.math.sin(dLat / 2).let { it * it } +
        kotlin.math.cos(rad(aLat)) * kotlin.math.cos(rad(bLat)) * kotlin.math.sin(dLon / 2).let { it * it }
    return 2 * r * kotlin.math.asin(kotlin.math.sqrt(h.coerceIn(0.0, 1.0)))
}

/** "2.3 mi (3.7 km)". */
internal fun milesKm(m: Double): String {
    fun one(x: Double) = (kotlin.math.round(x * 10) / 10).let { if (it == kotlin.math.floor(it)) it.toLong().toString() else it.toString() }
    return "${one(m / 1609.344)} mi (${one(m / 1000)} km)"
}

/**
 * The answer for the model: what was searched near, as it was found, then
 * the [count] nearest of [found], each with how far, its address, today's
 * hours and its phone where the search has them.
 */
internal fun placesAnswer(query: String, anchor: FoundPlace, found: List<FoundPlace>, count: Int): String {
    val from = listOf(anchor.name, anchor.address).filter { it.isNotBlank() }.distinct().joinToString(", ")
    val nearest = found.map { it to distanceM(anchor.lat, anchor.lon, it.lat, it.lon) }.sortedBy { it.second }.take(count)
    if (nearest.isEmpty()) return "No \"$query\" found near $from."
    return buildString {
        append("Nearest \"").append(query).append("\" to ").append(from).append(", straight-line:")
        nearest.forEachIndexed { i, (p, m) ->
            append('\n').append(i + 1).append(". ").append(p.name).append(", ").append(milesKm(m))
            if (p.address.isNotBlank()) append(": ").append(p.address)
            if (p.hoursToday.isNotBlank()) append(". Open today ").append(p.hoursToday)
            if (p.phone.isNotBlank()) append(". ").append(p.phone)
        }
    }
}

/** Parse a Brave web-search response body into a compact, model-readable
 *  ranked list. Strips the `<strong>` highlight tags Brave wraps matched
 *  terms in. Returns a "no results" sentinel rather than throwing on any
 *  shape it doesn't recognise. */
internal fun parseBraveResults(json: String, max: Int): String {
    val obj = runCatching { BraveSearchClient.JSON.parseToJsonElement(json).jsonObject }
        .getOrNull() ?: return "Web search returned an unreadable response."
    val results = (obj["web"] as? JsonObject)?.get("results")
        ?.let { runCatching { it.jsonArray }.getOrNull() }
        ?: return "No web results."
    if (results.isEmpty()) return "No web results."
    val tags = Regex("<[^>]+>")
    return results.take(max).mapIndexedNotNull { i, el ->
        val o = el as? JsonObject ?: return@mapIndexedNotNull null
        val title = o["title"]?.jsonPrimitive?.contentOrNull?.replace(tags, "")?.trim().orEmpty()
        val link = o["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val desc = o["description"]?.jsonPrimitive?.contentOrNull?.replace(tags, "")?.trim().orEmpty()
        if (title.isBlank() && link.isBlank()) return@mapIndexedNotNull null
        buildString {
            append("${i + 1}. ").append(title.ifBlank { link })
            if (link.isNotBlank()) append("\n   ").append(link)
            if (desc.isNotBlank()) append("\n   ").append(desc.take(300))
        }
    }.joinToString("\n\n").ifBlank { "No web results." }
}
