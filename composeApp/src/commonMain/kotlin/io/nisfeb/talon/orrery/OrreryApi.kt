package io.nisfeb.talon.orrery

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Orrery's HTTP API on the viewer's own ship, at `/apps/orrery/api`.
 *
 * Two clients, on purpose. The owner's cookie mints and revokes this
 * install's key and asks whether orrery is there at all. Everything
 * the pipe writes goes under that key, on a client that carries no
 * cookie: a request with both would be the owner's, and the ship
 * forces `by` to the key's identity only when the key is what it saw.
 */
class OrreryApi(
    private val owner: HttpClient,
    private val bare: HttpClient,
    baseUrl: String,
) {
    private val root = baseUrl.trimEnd('/') + APP_PATH

    /** Whether orrery answers on this ship, by the cheapest owner read. */
    suspend fun probe(): OrreryAvailability {
        val resp = send(owner, "/api/state?kind=none") { method = HttpMethod.Get }
        return when {
            resp.status.isSuccess() -> OrreryAvailability.PRESENT
            resp.status.value == NOT_FOUND -> OrreryAvailability.MISSING
            resp.status.value == FORBIDDEN -> OrreryAvailability.SIGNED_OUT
            else -> throw OrreryError.Refused(resp.status.value, reasonOf(reading { resp.bodyAsText() }))
        }
    }

    /**
     * A key for this install. The secret comes back once; the ship keeps
     * a hash. The scope is what the structural pipe and, later, the
     * triage need and nothing more: it may see and write people, places,
     * things, situations and orgs, and propose the four action kinds.
     */
    suspend fun mint(name: String, by: String): MintedKey {
        val body = buildJsonObject {
            put("name", name)
            put("by", by)
            putJsonObject("scope") {
                putJsonArray("kinds") { KINDS.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                putJsonArray("actions") { ACTIONS.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                put("write", true)
            }
        }
        val o = reading { Json.parseToJsonElement(request(owner, HttpMethod.Post, "/api/clients", body.toString())).jsonObject }
        return MintedKey(
            id = o["id"]?.jsonPrimitive?.content ?: throw OrreryError.Garbled(IllegalStateException("no id")),
            token = o["token"]?.jsonPrimitive?.content ?: throw OrreryError.Garbled(IllegalStateException("no token")),
        )
    }

    suspend fun revoke(id: String) {
        request(owner, HttpMethod.Delete, "/api/clients/$id")
    }

    /** The bodies the key may see, with the rev the view was at. */
    suspend fun state(token: String): StateView {
        val text = request(bare, HttpMethod.Get, "/api/state") { header(HttpHeaders.Authorization, "Bearer $token") }
        val o = reading { Json.parseToJsonElement(text).jsonObject }
        val bodies = o["bodies"]?.jsonArray.orEmpty().mapNotNull { e ->
            val b = e.jsonObject
            KnownBody(
                id = b["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                name = b["name"]?.jsonPrimitive?.content,
                aliases = b["aliases"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.content },
                ship = b["ship"]?.jsonPrimitive?.content?.takeIf { it.startsWith("~") },
            )
        }
        return StateView(rev = o["rev"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L, bodies = bodies)
    }

    /** One observe batch under the key. Per-item answers, in order. */
    suspend fun observe(batch: JsonObject, token: String): ObserveAnswer {
        val text = request(bare, HttpMethod.Post, "/api/observe", batch.toString()) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val o = reading { Json.parseToJsonElement(text).jsonObject }
        fun items(k: String) = o[k]?.jsonArray.orEmpty().map { e ->
            val i = e.jsonObject
            ItemAnswer(
                id = i["id"]?.jsonPrimitive?.content,
                ok = i["ok"]?.jsonPrimitive?.booleanOrNull ?: false,
                existing = i["existing"]?.jsonPrimitive?.booleanOrNull ?: false,
                error = i["error"]?.jsonPrimitive?.content,
            )
        }
        return ObserveAnswer(bodies = items("bodies"), observations = items("observations"))
    }

    private suspend fun request(
        client: HttpClient,
        method: HttpMethod,
        path: String,
        body: String? = null,
        extra: HttpRequestBuilder.() -> Unit = {},
    ): String {
        val resp = send(client, path) {
            this.method = method
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            extra()
        }
        val text = reading { resp.bodyAsText() }
        if (!resp.status.isSuccess()) throw OrreryError.Refused(resp.status.value, reasonOf(text))
        return text
    }

    /** Anything that stops a request arriving is [OrreryError.Unreachable]. */
    private suspend fun send(client: HttpClient, path: String, build: HttpRequestBuilder.() -> Unit): HttpResponse =
        try {
            client.request(root + path, build)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            throw OrreryError.Unreachable(t)
        }

    private inline fun <T> reading(block: () -> T): T = try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        throw OrreryError.Garbled(t)
    }

    private fun reasonOf(text: String): String =
        runCatching { Json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
            ?: text.take(160).ifBlank { "no reason given" }

    companion object {
        const val APP_PATH = "/apps/orrery"
        private const val NOT_FOUND = 404
        private const val FORBIDDEN = 403
        val KINDS = listOf("person", "place", "thing", "situation", "org")
        val ACTIONS = listOf("task", "note", "message", "calendar")
    }
}

enum class OrreryAvailability {
    /** Not probed yet this session. */
    UNKNOWN,
    /** Orrery answered. */
    PRESENT,
    /** Nothing at its path: orrery is not installed, or Grubbery is not. */
    MISSING,
    /** The ship refused the cookie. */
    SIGNED_OUT,
}

data class MintedKey(val id: String, val token: String)

data class StateView(val rev: Long, val bodies: List<KnownBody>)

data class ItemAnswer(val id: String?, val ok: Boolean, val existing: Boolean, val error: String?)

data class ObserveAnswer(val bodies: List<ItemAnswer>, val observations: List<ItemAnswer>) {
    val refused: List<ItemAnswer> get() = (bodies + observations).filter { !it.ok }
}

sealed class OrreryError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Refused(val status: Int, val reason: String) : OrreryError("HTTP $status: $reason")
    class Unreachable(cause: Throwable) : OrreryError("no answer from the ship", cause)
    class Garbled(cause: Throwable) : OrreryError("the ship's answer could not be read", cause)
}
