package io.nisfeb.talon.orrery

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
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
     * a hash. Every kind and action kind the ship has, writing, and
     * sensitive: write, because the owner's reply to the brief names
     * health, which the key may then file without ever reading it back.
     */
    suspend fun mint(
        name: String,
        by: String,
        kinds: List<String> = KINDS,
        actions: List<String> = ACTIONS,
    ): MintedKey {
        val body = buildJsonObject {
            put("name", name)
            put("by", by)
            putJsonObject("scope") {
                putJsonArray("kinds") { kinds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                putJsonArray("actions") { actions.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                put("write", true)
                put("sensitive", "write")
            }
        }
        // The request outside [reading]: a refusal inside it was reported
        // as an answer that could not be read, and lost the ship's reason.
        val text = request(owner, HttpMethod.Post, "/api/clients", body.toString())
        val o = reading { Json.parseToJsonElement(text).jsonObject }
        return MintedKey(
            id = o["id"]?.jsonPrimitive?.content ?: throw OrreryError.Garbled(IllegalStateException("no id")),
            token = o["token"]?.jsonPrimitive?.content ?: throw OrreryError.Garbled(IllegalStateException("no token")),
        )
    }

    suspend fun revoke(id: String) {
        request(owner, HttpMethod.Delete, "/api/clients/$id")
    }

    /** The state view as the key sees it, whole, for readers that need attribute values. */
    suspend fun stateJson(token: String): JsonObject {
        val text = request(bare, HttpMethod.Get, "/api/state") { header(HttpHeaders.Authorization, "Bearer $token") }
        return reading { Json.parseToJsonElement(text).jsonObject }
    }

    /** The whole schema, which only the owner may read: what a key's scope is measured against. */
    suspend fun schema(): JsonObject {
        val text = request(owner, HttpMethod.Get, "/api/schema")
        return reading { Json.parseToJsonElement(text).jsonObject }
    }

    /**
     * What the on-ship generator's last pass did. The owner's route, so
     * the owner's session reads it; a ship whose orrery has no generator
     * yet answers nothing, and so does this.
     */
    suspend fun generatorLast(): GeneratorRun? {
        val text = runCatching { request(owner, HttpMethod.Get, "/api/generator/last") }.getOrNull() ?: return null
        val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        return generatorRunOf(o)
    }

    /** The generator's settings, the owner's route; its key is only ever said to be set. */
    suspend fun generatorSettings(): GeneratorSettings? {
        val text = runCatching { request(owner, HttpMethod.Get, "/api/generator") }.getOrNull() ?: return null
        return runCatching { generatorSettingsOf(Json.parseToJsonElement(text).jsonObject) }.getOrNull()
    }

    /**
     * One of the ship's settings documents, read and written whole.
     *
     * Generic on purpose. Orrery keeps its configuration in a handful
     * of owner-only docs under the same shape of route, so a client
     * that knows the shape needs to know nothing about what any one of
     * them is for: what the telegram reader wants is orrery's business
     * and the ship's, not this app's. New docs cost nothing here.
     *
     * The ship masks credentials on the way out, and a blank credential
     * on the way in keeps what the ship has; a field left out is kept
     * and one sent as null is cleared. All its rules, not ours.
     *
     * [name] is checked against [SETTINGS] rather than passed through:
     * it lands in a URL path, and the caller is sometimes a model.
     */
    suspend fun settingsDoc(name: String): String {
        require(name in SETTINGS || name in LISTS) { "no settings document called $name" }
        return request(owner, HttpMethod.Get, "/api/$name")
    }

    /**
     * Whether a key minted just now works yet. The ship answers the mint
     * before its writer stores the key, and a key it has not stored is
     * forbidden: used at once, that read as revoked and turned the pipe
     * straight back off.
     */
    suspend fun keyLanded(token: String): Boolean = settle {
        // The state narrowed to no kind: the key proved, and not the whole
        // view serialised on the ship's one thread to prove it.
        request(bare, HttpMethod.Get, "/api/state?kind=none") { header(HttpHeaders.Authorization, "Bearer $token") }
        true
    }

    /**
     * The ship answers a write before its writer applies it (write-then),
     * so a read straight after can still see things as they were. This
     * asks [seen] until it says yes, pausing longer each time, for about
     * six seconds; a read that fails counts as not seen. It never throws,
     * since whatever calls it has already changed something. The one
     * loop every read-back of a write goes through.
     */
    suspend fun settle(seen: suspend () -> Boolean): Boolean {
        var pause = SETTLE_FIRST_MS
        repeat(SETTLE_READS) { n ->
            if (n > 0) {
                kotlinx.coroutines.delay(pause)
                pause *= 2
            }
            if (runCatching { seen() }.getOrDefault(false)) return true
        }
        return false
    }

    /**
     * Merge [body] into a settings document. Owner's route, owner's
     * client. The ship answers once the write has landed, with the
     * document as its GET gives it: that answer is the new state, and
     * reading it again straight after adds nothing.
     */
    suspend fun setSettingsDoc(name: String, body: JsonObject): String {
        require(name in SETTINGS) { "no settings document called $name" }
        return request(owner, HttpMethod.Put, "/api/$name", body.toString())
    }

    /** Run the ship's chat reader now; [settingsDoc] `chat/last` is its record once it is done. */
    suspend fun wakeChat() {
        request(owner, HttpMethod.Post, "/api/chat/wake")
    }

    /**
     * Register a settings document with the service it names, and read
     * back what that service holds. Owner's route, owner's client. The
     * ship makes the outside call itself; this app never holds the
     * token. [name] is allow-listed like [settingsDoc], for the same reason.
     */
    suspend fun register(name: String): String =
        request(owner, HttpMethod.Post, registrationPath(name))

    suspend fun registration(name: String): String =
        request(owner, HttpMethod.Get, registrationPath(name))

    private fun registrationPath(name: String): String =
        REGISTRATIONS[name] ?: throw IllegalArgumentException("no registration for $name")

    /** The bodies the key may see, with the rev the view was at. */
    suspend fun state(token: String): StateView = viewOf(stateJson(token))

    /** Propose an action under the key: its id and the status policy gave it, or the open twin's. */
    suspend fun act(action: JsonObject, token: String): Pair<String, String> {
        val text = request(bare, HttpMethod.Post, "/api/act", action.toString()) { header(HttpHeaders.Authorization, "Bearer $token") }
        val o = reading { Json.parseToJsonElement(text).jsonObject }
        val id = o["id"]?.jsonPrimitive?.content ?: throw OrreryError.Garbled(IllegalStateException("no id"))
        return id to (o["status"]?.jsonPrimitive?.content ?: "proposed")
    }

    /** A state view already read, parsed: bodies by name, and the vocabulary the key may use. */
    fun viewOf(o: JsonObject): StateView {
        val bodies = o["bodies"]?.jsonArray.orEmpty().mapNotNull { e ->
            val b = e.jsonObject
            KnownBody(
                id = b["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                name = b["name"]?.jsonPrimitive?.content,
                aliases = b["aliases"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.content },
                ship = b["ship"]?.jsonPrimitive?.content?.takeIf { it.startsWith("~") },
            )
        }
        // The ship's vocabulary per kind, trimmed to what the key may see.
        val kinds = o["schema"]?.jsonObject?.get("kinds")?.jsonObject.orEmpty()
        val attrs = kinds.mapValues { (_, k) ->
            k.jsonObject["attrs"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.content }
        }
        // What the owner says those attributes mean, where they have
        // said: a ship seeded before version 11 says nothing here.
        val notes = kinds.mapValues { (_, k) ->
            k.jsonObject["notes"]?.jsonObject.orEmpty()
                .mapNotNull { (attr, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.let { attr to it } }
                .toMap()
        }.filterValues { it.isNotEmpty() }
        return StateView(o["rev"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L, bodies, attrs, notes, o["schema"] as? JsonObject ?: JsonObject(emptyMap()))
    }

    /**
     * Bodies whose name, alias or ship matches [q], exact first, as the
     * ship itself judges a match: version 10 and later reads aliases
     * and name words too. Asking before creating is what stops one
     * event becoming two bodies.
     */
    suspend fun resolve(q: String, token: String): List<ResolvedBody> {
        if (q.isBlank()) return emptyList()
        val text = request(bare, HttpMethod.Get, "/api/resolve?q=" + q.encodeURLParameter()) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val arr = reading { Json.parseToJsonElement(text) } as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e.jsonObject
            ResolvedBody(
                id = o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                kind = o["kind"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                name = o["name"]?.jsonPrimitive?.content ?: "",
                match = o["match"]?.jsonPrimitive?.content ?: "",
            )
        }
    }

    /**
     * One body's timeline as the ship keeps it: what was said about it,
     * when, from where, and whether the row still stands. Read before
     * taking anything back, because an observation's id is a hash the
     * ship computes and no client can work out for itself.
     */
    suspend fun observationsOf(id: String, token: String): List<KnownObs> {
        val text = request(bare, HttpMethod.Get, "/api/body/$id") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val rows = reading { Json.parseToJsonElement(text).jsonObject }["observations"]?.jsonArray.orEmpty()
        return rows.mapNotNull { e ->
            val o = e.jsonObject
            KnownObs(
                id = o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                attr = o["attr"]?.jsonPrimitive?.content ?: "",
                atMs = o["at"]?.jsonPrimitive?.content
                    ?.let { runCatching { kotlinx.datetime.Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
                    ?: return@mapNotNull null,
                sourceId = o["source"]?.jsonObject?.get("id")?.jsonPrimitive?.content ?: "",
                status = o["status"]?.jsonPrimitive?.content ?: "",
                sourceKind = o["source"]?.jsonObject?.get("kind")?.jsonPrimitive?.content ?: "",
            )
        }
    }

    /**
     * This ship's minted keys, as the owner sees them: no secret, but
     * when each was last used, to the hour. The owner cookie only; a
     * phone holds it too, which is how it knows a computer is on the
     * job.
     */
    suspend fun clients(): List<ClientKey> {
        val text = request(owner, HttpMethod.Get, "/api/clients")
        val el = reading { Json.parseToJsonElement(text) }
        val arr = (el as? kotlinx.serialization.json.JsonArray) ?: el.jsonObject["clients"]?.jsonArray.orEmpty()
        return arr.mapNotNull { e ->
            val c = e.jsonObject
            ClientKey(
                id = c["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                by = c["by"]?.jsonPrimitive?.content ?: "",
                usedMs = c["used"]?.jsonPrimitive?.content?.takeIf { it != "null" }?.let { runCatching { kotlinx.datetime.Instant.parse(it).toEpochMilliseconds() }.getOrNull() },
            )
        }
    }

    /**
     * The open actions, newest first as the ship lists them: under the
     * key when there is one, else as the owner, who may always see and
     * answer them. Reviewing a proposal is the owner's act, so it does
     * not wait on this install having a key.
     */
    suspend fun actions(token: String?, status: String = "open"): List<OrreryAction> {
        val text = if (token != null) {
            request(bare, HttpMethod.Get, "/api/actions?status=$status") { header(HttpHeaders.Authorization, "Bearer $token") }
        } else {
            request(owner, HttpMethod.Get, "/api/actions?status=$status")
        }
        val arr = reading { Json.parseToJsonElement(text) }.let { it as? kotlinx.serialization.json.JsonArray ?: it.jsonObject["actions"]?.jsonArray }.orEmpty()
        return arr.mapNotNull { e -> actionOf(e.jsonObject) }
    }

    /** One action as the ship gives it, or null where it gives no id or kind. */
    private fun actionOf(a: JsonObject): OrreryAction? = OrreryAction(
        id = a["id"]?.jsonPrimitive?.contentOrNull ?: return null,
        kind = a["kind"]?.jsonPrimitive?.contentOrNull ?: return null,
        title = a["title"]?.jsonPrimitive?.contentOrNull ?: "",
        payload = a["payload"] as? JsonObject ?: JsonObject(emptyMap()),
        about = a["about"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull },
        due = a["due"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" },
        status = a["status"]?.jsonPrimitive?.contentOrNull ?: "proposed",
        by = a["by"]?.jsonPrimitive?.contentOrNull ?: "",
    )

    /**
     * What the owner typed under a proposal, sent to the ship to refine
     * it (rule 17, orrery 36). The ship reads it against the action,
     * the schema's shapes and the bodies the words could mean, and
     * answers with the action revised in place, whatever else the text
     * asked for as its own proposals, and a note where it would not.
     *
     * The action stays proposed: the owner approves what the ship
     * actually holds, with the same tap as before.
     */
    suspend fun refine(token: String?, id: String, text: String): Refined {
        // The ship takes up to 2000 bytes here, not the 500 of a note.
        val body = buildJsonObject { put("text", clipBytes(text.trim(), 2000)) }
        // A refusal is an answer the owner reads, not a failure of the
        // call: 409 for an action that has moved or a kind the ship
        // does not refine, 404 for one this key cannot see, 403 for a
        // key that may not write. All three carry the note.
        val said = runCatching {
            if (token != null) {
                request(bare, HttpMethod.Post, "/api/actions/$id/refine", body.toString(), refineTimeout) {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            } else {
                request(owner, HttpMethod.Post, "/api/actions/$id/refine", body.toString(), refineTimeout)
            }
        }.getOrElse { e ->
            val why = (e as? OrreryError.Refused)?.reason ?: e.message ?: "The ship did not answer."
            return Refined(null, emptyList(), why)
        }
        val o = reading { Json.parseToJsonElement(said).jsonObject }
        val note = o["note"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (o["ok"]?.jsonPrimitive?.contentOrNull == "false" || o["ok"]?.toString() == "false") {
            return Refined(null, emptyList(), note.ifBlank { "The ship would not take that." })
        }
        return Refined(
            action = (o["action"] as? JsonObject)?.let { actionOf(it) },
            extras = (o["extras"] as? kotlinx.serialization.json.JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.let { e -> actionOf(e) } },
            note = note,
        )
    }

    /** Move an action: approved, done, dismissed or failed, with a note where one is due. */
    suspend fun transition(token: String?, id: String, status: String, note: String = "") {
        val body = buildJsonObject { put("status", status); if (note.isNotBlank()) put("note", clipBytes(note.trim(), 500)) }
        if (token != null) {
            request(bare, HttpMethod.Post, "/api/actions/$id", body.toString()) { header(HttpHeaders.Authorization, "Bearer $token") }
        } else {
            request(owner, HttpMethod.Post, "/api/actions/$id", body.toString())
        }
    }

    /**
     * Whether action [id] shows [status] yet. The ship answers a change,
     * and a new action's id, before its writer applies them, so what
     * follows at once can still see the action as it was: read back a
     * few times.
     */
    suspend fun landed(token: String, id: String, status: String): Boolean =
        settle { actions(token, status).any { it.id == id } }

    /**
     * Ask the ship for a pass now, rather than at the top of the next
     * hour (rule 16). [about] is what the pass looks at first: the
     * situations the facts were about, at most five, and an empty list
     * where they were only about the owner. Nothing about urgency is
     * stored; the pass is earlier, that is all. Counted against the
     * owner's own cap, so the sixth in a day answers held, which is an
     * answer and not a failure.
     */
    suspend fun generate(token: String, about: List<String>): String {
        val body = buildJsonObject {
            putJsonArray("about") { about.forEach { add(JsonPrimitive(it)) } }
        }
        val text = request(bare, HttpMethod.Post, "/api/generate", body.toString()) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        return runCatching { Json.parseToJsonElement(text).jsonObject["status"]?.jsonPrimitive?.contentOrNull }
            .getOrNull() ?: "asked"
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
        /** Longer than the usual, for a route that waits on a model call. */
        timeoutMs: Long? = null,
        extra: HttpRequestBuilder.() -> Unit = {},
    ): String {
        val resp = send(client, path) {
            this.method = method
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            timeoutMs?.let {
                timeout {
                    requestTimeoutMillis = it
                    socketTimeoutMillis = it
                }
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
        runCatching {
            val o = Json.parseToJsonElement(text).jsonObject
            // A refusal carries the same words under both names, and
            // the note is the one written for the owner to read.
            o["note"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: o["error"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
            ?: text.take(160).ifBlank { "no reason given" }

    companion object {
        /**
         * A refinement is a model call on the ship, and the route waits
         * for the writer to land the revision before it reads the
         * action back: about ten seconds, says rule 17, so the wait
         * here is not the one every other route gets.
         */
        private const val refineTimeout = 45_000L

        /** A settle's reads and first pause: 200 ms doubling, about six seconds in all. */
        const val SETTLE_READS = 6
        const val SETTLE_FIRST_MS = 200L

        /**
         * The settings documents the owner may read and write through
         * [settingsDoc]. An allow-list because the name is part of a
         * URL and a model sometimes chooses it: anything outside this
         * set is a mistake, and `../` is not a document.
         */
        val SETTINGS = setOf("generator", "telegram", "schema", "policy", "chat", "mail", "preferences")

        /** Read and never written: what the chat reader may pick from, and each reader's last pass. */
        val LISTS = setOf("chat/dms", "chat/channels", "chat/last", "mail/last", "telegram/last")

        /**
         * The documents that register themselves with an outside
         * service, and the route that does it: POST registers, GET
         * reads what the service holds. Today only the Telegram
         * webhook.
         */
        val REGISTRATIONS = mapOf("telegram" to "/api/telegram/webhook")

        const val APP_PATH = "/apps/orrery"
        private const val NOT_FOUND = 404
        private const val FORBIDDEN = 403
        // activity is in this list because a recurring event is one:
        // a key without it cannot see an activity, so it would resolve
        // nothing and make the situation twin all over again.
        val KINDS = listOf("person", "place", "thing", "situation", "org", "activity", "note")
        val ACTIONS = listOf("task", "note", "message", "calendar", "home")
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

/** A body the ship matched, and how. */
data class ResolvedBody(val id: String, val kind: String, val name: String, val match: String) {
    val isExact: Boolean get() = match == "exact"
}

/** One row of a body's timeline, as far as a client needs to read it. */
data class KnownObs(val id: String, val attr: String, val atMs: Long, val sourceId: String, val status: String, val sourceKind: String = "") {
    val stands: Boolean get() = status != "retracted"
}

/** One of this ship's keys, as the owner lists them. */
data class ClientKey(val id: String, val by: String, val usedMs: Long?)

/** Something the analyst proposed, as the ship holds it. */
data class OrreryAction(
    val id: String,
    val kind: String,
    val title: String,
    val payload: JsonObject,
    val about: List<String>,
    val due: String?,
    val status: String,
    val by: String,
)

/**
 * What a refinement answered: the action as the ship now holds it, the
 * proposals the text asked for beyond it, and what it would not do.
 * A null [action] with a note is a refusal, which changed nothing.
 */
data class Refined(
    val action: OrreryAction?,
    val extras: List<OrreryAction> = emptyList(),
    val note: String = "",
)

data class StateView(
    val rev: Long,
    val bodies: List<KnownBody>,
    val attrs: Map<String, List<String>> = emptyMap(),
    /** What the ship says each attribute means, by kind then attr. */
    val notes: Map<String, Map<String, String>> = emptyMap(),
    /** The schema as the key sees it: the action kinds it may use and their payload shapes. */
    val schema: JsonObject = JsonObject(emptyMap()),
)

/**
 * The ship's own reader of the owner's Tlon chats, as far as the screen
 * needs it, and [sendDms], whether the ship sends approved chat messages
 * (orrery 52), which lives in the same document.
 */
data class ChatReader(val enabled: Boolean, val dms: Set<String>, val channels: Set<String>, val sendDms: Boolean = false)

fun chatReaderOf(o: JsonObject) = ChatReader(
    enabled = o["enabled"]?.jsonPrimitive?.booleanOrNull == true,
    dms = names(o["dms"]).toSet(),
    channels = names(o["channels"]).toSet(),
    sendDms = o["send_dms"]?.jsonPrimitive?.booleanOrNull == true,
)

/**
 * The owner's own words, which every orrery prompt reads (orrery
 * f300be7): how they like things written, and what always or never to
 * do. The ship keeps one [list] and a write of it replaces it whole.
 */
data class OrreryPreferences(val style: String, val list: List<String>) {
    companion object {
        const val STYLE_BYTES = 1000
        const val MAX = 30
        const val ONE_BYTES = 300
    }
}

fun preferencesOf(o: JsonObject) = OrreryPreferences(
    style = o["style"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    list = names(o["preferences"]),
)

/** A DM or channel the chat reader could read: its id, and the name a person knows it by, which may be blank. */
data class ChatOption(val id: String, val name: String)

fun chatOptionsOf(o: JsonObject): List<ChatOption> = (o["items"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { e ->
    val item = e as? JsonObject ?: return@mapNotNull null
    val id = item["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
    ChatOption(id, item["name"]?.jsonPrimitive?.contentOrNull.orEmpty())
}

/** A reader's last pass (`chat/last`, `mail/last`): when, how much it read and filed, and what it said. */
data class ChatReaderRun(val atMs: Long?, val read: Int, val filed: Int, val notes: List<String>, val modelDown: Boolean)

fun chatReaderRunOf(o: JsonObject) = ChatReaderRun(
    atMs = o["at"]?.jsonPrimitive?.contentOrNull?.let { runCatching { kotlinx.datetime.Instant.parse(it).toEpochMilliseconds() }.getOrNull() },
    read = o["read"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
    filed = o["filed"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
    notes = names(o["notes"]),
    modelDown = o["down"] is JsonObject,
)

data class ItemAnswer(val id: String?, val ok: Boolean, val existing: Boolean, val error: String?)

data class ObserveAnswer(val bodies: List<ItemAnswer>, val observations: List<ItemAnswer>) {
    val refused: List<ItemAnswer> get() = (bodies + observations).filter { !it.ok }
}

sealed class OrreryError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Refused(val status: Int, val reason: String) : OrreryError("HTTP $status: $reason")
    class Unreachable(cause: Throwable) : OrreryError("no answer from the ship", cause)
    class Garbled(cause: Throwable) : OrreryError("the ship's answer could not be read", cause)
}

private fun names(a: kotlinx.serialization.json.JsonElement?): List<String> =
    (a as? kotlinx.serialization.json.JsonArray).orEmpty()
        .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }

fun schemaKinds(schema: JsonObject): List<String> = (schema["kinds"] as? JsonObject)?.keys?.toList().orEmpty()

fun schemaActions(schema: JsonObject): List<String> = names(schema["actions"])

/** What a reader may propose, rule 14; note and home are the generator's. */
val READER_ACTIONS = listOf("task", "calendar", "message")

/**
 * A payload held to the schema's shape for its kind, rule 14: every
 * `required` key present, a `one of` key holding a listed value, `to`
 * naming a body the ship has, and a time that parses, sent as UTC.
 * The payload as it should be sent, or why it is dropped.
 */
fun checkPayload(payload: JsonObject, shape: JsonObject, known: Set<String>): Pair<JsonObject?, String?> {
    val out = payload.toMutableMap()
    for ((k, spec) in shape) {
        val line = (spec as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: continue
        val said = (payload[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        if (said.isNullOrEmpty()) {
            if (line.startsWith("required") && payload[k] !is JsonObject) return null to "lacks $k"
            continue
        }
        ONE_OF.find(line)?.let { m ->
            val allowed = m.groupValues[1].split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            if (said.lowercase() !in allowed) return null to "$k is not one of ${allowed.joinToString(", ")}"
            out[k] = kotlinx.serialization.json.JsonPrimitive(said.lowercase())
        }
        if ("ISO 8601" in line) {
            val utc = runCatching { kotlinx.datetime.Instant.parse(said).toString() }.getOrNull()
                ?: said.takeIf { runCatching { kotlinx.datetime.LocalDate.parse(it) }.isSuccess }
                ?: return null to "$k is not a time"
            out[k] = kotlinx.serialization.json.JsonPrimitive(utc)
        }
    }
    val to = (payload["to"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
    if (to != null) {
        if (to !in known) return null to "to names no body the ship has: $to"
        out["to"] = kotlinx.serialization.json.JsonPrimitive(to)
    }
    return JsonObject(out) to null
}

private val ONE_OF = Regex("one of ([^;]+)")

/**
 * Whether a key's schema view carries all of the owner's: every kind,
 * every attribute of each (a sensitive one shows only to a key that may
 * write it), and every action kind.
 */
fun scopeCovers(mine: JsonObject?, full: JsonObject): Boolean {
    if (mine == null) return false
    val theirs = (mine["kinds"] as? JsonObject).orEmpty()
    val kindsOk = (full["kinds"] as? JsonObject).orEmpty().all { (kind, spec) ->
        val have = theirs[kind] as? JsonObject ?: return@all false
        names((spec as? JsonObject)?.get("attrs")).all { it in names(have["attrs"]) }
    }
    return kindsOk && schemaActions(full).all { it in schemaActions(mine) }
}

/** [s] cut to at most [max] bytes of UTF-8, never inside a character: the ship counts bytes. */
fun clipBytes(s: String, max: Int): String {
    if (s.encodeToByteArray().size <= max) return s
    var end = s.length
    while (end > 0 && s.substring(0, end).encodeToByteArray().size > max) end--
    // Never leave half of a surrogate pair.
    if (end > 0 && s[end - 1].isHighSurrogate()) end--
    return s.substring(0, end)
}

/** What the ship's generator last did, as much of it as a line needs. */
/** The on-ship generator's settings, as far as Talon shows them. */
data class GeneratorSettings(val enabled: Boolean, val url: String?, val model: String?, val keySet: Boolean)

fun generatorSettingsOf(o: JsonObject) = GeneratorSettings(
    enabled = o["enabled"]?.jsonPrimitive?.booleanOrNull == true,
    url = o["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
    model = o["model"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
    // Said as a flag, or as the key's last four characters.
    keySet = o["api_key_set"]?.jsonPrimitive?.booleanOrNull == true || !o["api_key"]?.jsonPrimitive?.contentOrNull.isNullOrBlank(),
)

data class GeneratorRun(
    val atMs: Long?,
    val filed: Int?,
    val dropped: Int?,
    val skipped: Boolean,
    val notes: List<String>,
    val costUsd: Double?,
    val error: String?,
    val callsToday: Int?,
)

/** The ship's `generator-last` document, read loosely: a pass that only called writes less. */
fun generatorRunOf(o: JsonObject): GeneratorRun {
    fun str(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
    fun int(k: String) = str(k)?.toDoubleOrNull()?.toInt()
    val at = (str("at") ?: str("called"))?.let { runCatching { kotlinx.datetime.Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
    val usage = o["usage"] as? JsonObject
    return GeneratorRun(
        atMs = at,
        filed = int("filed"),
        dropped = int("dropped"),
        skipped = str("skipped") == "true",
        notes = (o["notes"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull },
        costUsd = (usage?.get("cost") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toDoubleOrNull(),
        error = str("error"),
        callsToday = int("calls_today"),
    )
}

/** One line for the top of Actions: when, what it did, what it cost, and what stopped it. */
fun generatorLine(r: GeneratorRun, nowMs: Long, zone: kotlinx.datetime.TimeZone): String {
    val parts = mutableListOf<String>()
    r.atMs?.let { at ->
        val day = kotlinx.datetime.Instant.fromEpochMilliseconds(at).toLocalDateTime(zone).date
        val today = kotlinx.datetime.Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date
        val clock = OrreryText.clock(at, zone)
        parts += if (day == today) "ran $clock" else "ran ${day.dayOfMonth} ${day.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)} $clock"
    }
    when {
        r.error != null -> parts += "failed: ${r.error.take(80)}"
        r.skipped -> {
            val held = r.notes.firstOrNull { "held by the limits" in it }
            val until = held?.substringAfter(" until ", "")?.trim()
                ?.let { runCatching { kotlinx.datetime.Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
            parts += when {
                until != null -> "held by the limits until ${OrreryText.clock(until, zone)}"
                held != null -> "held by the limits"
                else -> "nothing new to ask about"
            }
        }
        else -> {
            r.filed?.let { parts += "filed $it" }
            r.dropped?.takeIf { it > 0 }?.let { parts += "dropped $it" }
        }
    }
    r.costUsd?.let { parts += "$" + (kotlin.math.round(it * 1000) / 1000).toString() }
    r.callsToday?.let { parts += if (it == 1) "1 call today" else "$it calls today" }
    return "Generator: " + parts.joinToString(", ").ifEmpty { "no run yet" }
}
