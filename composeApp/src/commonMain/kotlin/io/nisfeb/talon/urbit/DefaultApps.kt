package io.nisfeb.talon.urbit

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Talon's apps on the user's own ship without a tap, once per sign-in, as
 * %trunk is ([io.nisfeb.talon.call.CallController]): Grubbery where it is
 * plainly missing, and every app its shell stocks (lattice, mail, the
 * calendar, armillary, orrery) that is not there. So a ship that signs in
 * has what the AI and the assistant run on, rather than an Apps page to
 * find first. Quiet on failure: the Apps page still offers each install.
 *
 * Only what is missing, by name. The shell's "sync all" points a desk that
 * follows somewhere else (~nisfeb's own armillary) back at the published
 * one, and the shell's list counts a desk with any source as there.
 */
object DefaultApps {
    private val _asked = MutableStateFlow(0)

    /** Bumped once apps were asked for and their approvals wait, so the permits banner reads again. */
    val asked: StateFlow<Int> = _asked.asStateFlow()

    /**
     * Ask [shipUrl] for what it is missing; the names asked for. [http]
     * is the session's, which carries the owner's cookie. Anything short
     * of a clear answer (signed out, a 5xx, no answer) asks for nothing.
     */
    suspend fun ensure(
        http: HttpClient,
        shipUrl: String,
        poke: suspend (String, String, JsonElement) -> Boolean,
        pollMs: Long = this.pollMs,
        waitMs: Long = this.waitMs,
    ): List<String> {
        var stock = stockOf(http, shipUrl)
        if (stock is Stock.Absent) {
            val (app, mark, body) = LatticeInstall.installPoke()
            if (!poke(app, mark, body)) return emptyList()
            // The desk comes over the network. A shell that is new runs
            // its own first sync of every stock app; what it has not got
            // to yet is asked for below, which it takes the same way.
            stock = waitFor(pollMs, waitMs) { stockOf(http, shipUrl).takeIf { it is Stock.Listed } }
                ?: return emptyList()
        }
        if (stock !is Stock.Listed) return emptyList()
        val before = io.nisfeb.talon.ui.fetchPendingPermits(http, shipUrl)?.size ?: 0
        val asked = stock.apps.filter { (name, synced) -> !synced && sync(http, shipUrl, name) }.map { it.first }
        if (asked.isNotEmpty()) {
            // A fetched app asks for its approvals once its code has come
            // and it has risen, which takes a while. The banner reads only
            // every half hour, so tell it when they are there.
            waitFor(pollMs, waitMs) {
                io.nisfeb.talon.ui.fetchPendingPermits(http, shipUrl)?.size?.takeIf { it > before }
            }?.let { _asked.value++ }
        }
        return asked
    }

    internal sealed interface Stock {
        /** No Grubbery: eyre has nothing at the shell's path for its owner. */
        data object Absent : Stock

        /** The shell's stock apps, each with whether its desk is there. */
        data class Listed(val apps: List<Pair<String, Boolean>>) : Stock

        /** Anything else: no answer, a 5xx, not the owner, eyre's login page. */
        data object Unknown : Stock
    }

    /**
     * The shell's stock list. Only a 404 is no Grubbery: a session eyre
     * has forgotten is sent to the login page, which reads as a 200 page
     * of HTML, and is not knowing, not absent.
     */
    internal suspend fun stockOf(http: HttpClient, shipUrl: String): Stock = try {
        val resp = http.get("${shipUrl.trimEnd('/')}/apps/grubbery/desks/stock")
        when {
            resp.status.value == 404 -> Stock.Absent
            !resp.status.isSuccess() || resp.contentType()?.match(ContentType.Application.Json) != true -> Stock.Unknown
            else -> (Json.parseToJsonElement(resp.bodyAsText()) as? JsonArray)?.let { arr ->
                Stock.Listed(
                    arr.mapNotNull { e ->
                        val o = e as? JsonObject ?: return@mapNotNull null
                        val name = o["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        name to (o["synced"]?.jsonPrimitive?.booleanOrNull == true)
                    },
                )
            } ?: Stock.Unknown
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Stock.Unknown
    }

    /**
     * Ask the shell for one stock app by name: shell.hoon's POST
     * /desks/sync reads `name` from the body. It answers "syncing" before
     * it starts, so a 200 is the ask taken; its desk shows in the list.
     */
    internal suspend fun sync(http: HttpClient, shipUrl: String, name: String): Boolean = try {
        http.post("${shipUrl.trimEnd('/')}/apps/grubbery/desks/sync") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("name", name) }.toString())
        }.status.isSuccess()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private suspend fun <T : Any> waitFor(pollMs: Long, waitMs: Long, read: suspend () -> T?): T? {
        var waited = 0L
        while (waited < waitMs) {
            delay(pollMs)
            waited += pollMs
            read()?.let { return it }
        }
        return null
    }

    /** How long after sign-in it starts: the first sync goes first. Vars for tests. */
    internal var afterStartMs = 20_000L
    internal var pollMs = 20_000L
    internal var waitMs = 5 * 60_000L
}
