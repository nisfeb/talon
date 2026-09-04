package io.nisfeb.talon.urbit

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.nisfeb.talon.util.ioDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Title + snippet for a urb:// address, for the inline preview card.
 *
 * Fetches the lattice `/fetch` endpoint on the viewer's own ship,
 * which returns the referent's body as gemtext JSON ({body, mark}).
 * The app's HTTP client already carries the ship session cookie for
 * that domain, so no manual auth is needed. Results (including "no
 * preview") are cached in memory, keyed by the urb:// address, which
 * is referentially transparent.
 */
object UrbUnfurlCache {

    data class Unfurl(val urbUrl: String, val title: String?, val snippet: String?)

    private sealed interface Entry {
        data object None : Entry
        data class Some(val unfurl: Unfurl) : Entry
    }

    private val lock = Mutex()
    private val results = HashMap<String, Entry>()
    private val inFlight = HashMap<String, Job>()
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun await(http: HttpClient, shipUrl: String, urbUrl: String): Unfurl? {
        lock.withLock { (results[urbUrl] as? Entry.Some)?.let { return it.unfurl } }
        lock.withLock { if (results.containsKey(urbUrl)) return null } // cached "None"
        return withContext(ioDispatcher) {
            val fetched = runCatching { fetch(http, shipUrl, urbUrl) }.getOrNull()
            lock.withLock {
                results[urbUrl] = if (fetched != null) Entry.Some(fetched) else Entry.None
            }
            fetched
        }
    }

    private suspend fun fetch(http: HttpClient, shipUrl: String, urbUrl: String): Unfurl? {
        val reader = UrbHttp.fetchUrl(shipUrl, urbUrl)
        val text = http.get(reader).bodyAsText()
        val body = json.parseToJsonElement(text).jsonObject["body"]
            ?.jsonPrimitive?.content ?: return null
        return unfurlOf(urbUrl, body)
    }

    /** Title (first heading) + snippet (first prose line) from a
     *  gemtext body. Internal so the parsing is unit-tested. */
    internal fun unfurlOf(urbUrl: String, gmi: String): Unfurl =
        Unfurl(urbUrl, titleOf(gmi), snippetOf(gmi))

    /** First gemtext heading ("# …"), or null. */
    private fun titleOf(gmi: String): String? =
        gmi.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("#") }
            ?.trimStart('#')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** First plain prose line — not a heading, link line, empty, or
     *  inside a ``` code fence. */
    private fun snippetOf(gmi: String): String? {
        var inFence = false
        for (raw in gmi.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("```")) {
                inFence = !inFence
                continue
            }
            if (inFence || line.isEmpty()) continue
            if (line.startsWith("#") || line.startsWith("=>")) continue
            return line.take(200)
        }
        return null
    }
}
