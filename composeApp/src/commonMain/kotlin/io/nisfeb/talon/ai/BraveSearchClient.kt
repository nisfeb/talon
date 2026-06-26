package io.nisfeb.talon.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

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
class BraveSearchClient(private val settings: () -> AiSettings.Config) {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String, count: Int): String {
        val cfg = settings()
        if (!cfg.webSearchEnabled) return "Web search is turned off in Settings."
        val key = cfg.braveApiKey.trim()
        if (key.isBlank()) return "No Brave Search API key is set in Settings."
        val q = query.trim()
        if (q.isBlank()) return "Error: query is required."
        val n = count.coerceIn(1, 20)

        val url = "https://api.search.brave.com/res/v1/web/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("count", n.toString())
            .build()
        val req = Request.Builder()
            .url(url)
            .header("X-Subscription-Token", key)
            .header("Accept", "application/json")
            .build()
        return withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        // 401/403 = bad/missing key; 429 = quota. Surface the
                        // code + a snippet so the user can tell which.
                        "Brave Search error ${resp.code}: ${body.take(200)}"
                    } else {
                        parseBraveResults(body, n)
                    }
                }
            }.getOrElse { "Web search failed: ${it.message ?: it::class.simpleName}" }
        }
    }

    companion object {
        internal val JSON = Json { ignoreUnknownKeys = true }
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
