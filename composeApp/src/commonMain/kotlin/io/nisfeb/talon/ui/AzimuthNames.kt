package io.nisfeb.talon.ui

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Word names for ships that are not comets.
 *
 * A comet carries its own fingerprint in its @p, so [Mnemonym] can
 * name one with no help. Everything else has to be looked up: the
 * ship's keys live on Azimuth, and its name is the fingerprint of
 * those keys ([AzimuthFingerprint]). That is what the reference
 * library's `+come` does, and the point of it is that an Azimuth ship
 * is a comet that never did the work to prove it is not a bot.
 *
 * Two things follow, and both shape this file.
 *
 * A name now costs a network round trip, and [ContactMap.displayName]
 * is a pure function called once per row and once per keystroke. So
 * nothing here blocks: [nameFor] only ever reads the cache, [warm]
 * fills it in the background, and [generation] bumps when the answers
 * change so rendered text re-renders instead of going stale.
 *
 * And it depends on the ship running %azimuth-rpc, which many do not.
 * A ship without it never answers, every lookup stays empty, and
 * every non-comet keeps its @p -- which is exactly what it showed
 * before any of this existed. Missing capability degrades to the old
 * behaviour rather than to an error.
 */
object AzimuthNames {

    private val lock = SynchronizedObject()
    /** Full and abridged name per ship, or null for "asked; has none".
     *  Encoded once at warm time: a name is read per row and per
     *  keystroke, and each encode is a SHA-256 and a bignum fold. */
    private val cache = HashMap<String, Pair<String, String>?>()

    /**
     * Whether ships that are not comets get a word name at all.
     *
     * A comet's name is free and intrinsic, so it is never a choice.
     * This is, because it is a different thing: a name that had to be
     * fetched, for an identity that never did the work a comet did.
     *
     * Off unless somebody asks for it. Turning it on has the client
     * look up every planet and moon it shows, and a name nobody
     * requested is not worth a lookup nobody expected. A reader who
     * wants them turns it on; until then a planet is its @p, which is
     * what it has always been.
     *
     * The platform UiSettings loads the stored value over this default
     * at startup and wires [persist].
     */
    val enabled = MutableStateFlow(false)

    /** Wired by the platform UiSettings at startup. */
    var persist: (Boolean) -> Unit = {}

    fun setEnabled(value: Boolean) {
        if (enabled.value == value) return
        enabled.value = value
        // A failed disk write must not take down the caller; the value
        // still applied live.
        runCatching { persist(value) }
        generation.value = generation.value + 1
    }

    /** Bumped whenever [nameFor] would start giving new answers, so
     *  caches of rendered text know to drop what they have. */
    val generation = MutableStateFlow(0)

    /** True once a lookup has come back, so callers can tell "this
     *  ship has no name" from "this ship has not been asked yet". */
    fun known(ship: String): Boolean = synchronized(lock) { cache.containsKey(ship) }

    /**
     * The abridged word name for [ship], or null when it is not known
     * yet, has no keys, or the ship cannot be looked up. Pure: never
     * fetches, never blocks.
     */
    fun nameFor(ship: String): String? = synchronized(lock) { cache[ship] }?.second

    /** The unabridged word name, for telling two ships apart whose
     *  short names came out the same. */
    fun fullNameFor(ship: String): String? = synchronized(lock) { cache[ship] }?.first

    /** Forget everything, for a ship switch. */
    fun reset() {
        synchronized(lock) { cache.clear() }
        generation.value = generation.value + 1
    }

    /**
     * Look up any of [ships] not already cached. Safe to call often;
     * already-known ships cost nothing. Failures are cached as "no
     * name" so a ship whose host has no %azimuth-rpc is asked once
     * rather than on every recomposition.
     */
    suspend fun warm(ships: Collection<String>, rpc: AzimuthRpc) {
        val wanted = synchronized(lock) {
            ships.filter { it !in cache && wantsLookup(it) }.distinct()
        }
        if (wanted.isEmpty()) return
        for (ship in wanted) {
            // Only an answer is remembered. "This ship has no keys" is
            // an answer; "the request failed" is not, and caching it
            // left every planet nameless after one offline launch,
            // with nothing that would ever ask again.
            val answer = rpc.fingerprint(ship).getOrNull() ?: continue
            val names = answer.fingerprint?.let { fig ->
                val full = Mnemonym.encodeFingerprint(fig) ?: return@let null
                full to (Mnemonym.displayFingerprint(fig) ?: full)
            }
            synchronized(lock) { cache[ship] = names }
            // Each name redraws the rows as it lands, rather than every
            // planet staying nameless until the last of them answers.
            if (names != null) generation.value = generation.value + 1
        }
    }

    /**
     * Keep the cache filled for the ships a reader might see, for as
     * long as the caller's scope lives.
     *
     * One definition, called once per host, rather than a lookup
     * hung off each of the seventeen places a ContactMap gets built:
     * those would each fetch the same ships, and the two hosts would
     * drift apart the way they have before.
     *
     * Nothing happens while the setting is off, so a reader who does
     * not want their client looking ships up does not have it done
     * anyway.
     */
    suspend fun keepWarm(
        contacts: kotlinx.coroutines.flow.Flow<List<io.nisfeb.talon.data.ContactEntity>>,
        rpc: AzimuthRpc,
    ) {
        enabled.collectLatest { on ->
            if (!on) return@collectLatest
            contacts.collect { list -> warm(list.map { it.ship }, rpc) }
        }
    }

    /** Comets name themselves; galaxies and stars are short already.
     *  That leaves planets and moons, which are what Azimuth knows. */
    internal fun wantsLookup(ship: String): Boolean {
        if (!ship.startsWith("~")) return false
        val syllables = ship.drop(1).replace("-", "").length
        return syllables == 12 || syllables == 24
    }
}

/**
 * Whether [ship] is a comet, by the length of its @p: sixteen syllables
 * where a planet has four and a moon eight.
 *
 * Worth asking before reaching for Azimuth. A comet has no Azimuth
 * point at all — its name IS the fingerprint of its keys — and a
 * Groundwire comet's credential is attested on Bitcoin instead.
 */
internal fun isComet(ship: String): Boolean =
    ship.startsWith("~") && ship.drop(1).replace("-", "").length == 48

/** Reads a ship's Azimuth keys. Separate so the naming path can be
 *  tested without a network, and so a host with no %azimuth-rpc can
 *  wire [None] instead. */
interface AzimuthRpc {

    /** What a lookup said. [fingerprint] is null when the ship has no
     *  keys or is unknown to Azimuth -- an answer, and cached. */
    data class Answer(val fingerprint: ByteArray?)

    /** A successful [Answer], or a failure when the ship could not be
     *  asked at all -- which is not cached, and is asked again. */
    suspend fun fingerprint(ship: String): kotlin.Result<Answer>

    /** A ship's public keys, as Azimuth holds them. */
    data class Keys(val auth: String, val crypt: String, val suite: Int)

    /** [Keys] for a ship, or null where Azimuth holds none — which
     *  includes every comet, Groundwire's among them: those are attested
     *  on Bitcoin and Azimuth has no point for them. Failure is the
     *  lookup itself not happening. */
    suspend fun keys(ship: String): kotlin.Result<Keys?> =
        kotlin.Result.failure(IllegalStateException("no azimuth-rpc"))

    companion object {
        /** For hosts with no %azimuth-rpc: nothing can be asked. */
        val None: AzimuthRpc = object : AzimuthRpc {
            override suspend fun fingerprint(ship: String): kotlin.Result<Answer> =
                kotlin.Result.failure(IllegalStateException("no azimuth-rpc"))
        }
    }
}

/**
 * Talks to the ship's own %azimuth-rpc agent, which binds /v1/azimuth
 * and speaks JSON-RPC. Its `getPoint` answers with the keys Azimuth
 * holds for a ship, which is everything a fingerprint needs.
 *
 * Nothing leaves the user's ship: this is their own Azimuth mirror,
 * not a third party being told who they are looking at.
 */
class EyreAzimuthRpc(
    private val http: HttpClient,
    private val baseUrl: String,
) : AzimuthRpc {

    override suspend fun fingerprint(ship: String): kotlin.Result<AzimuthRpc.Answer> = runCatching {
        AzimuthRpc.Answer(keysObject(ship)?.let(::fingerprintOf))
    }

    override suspend fun keys(ship: String): kotlin.Result<AzimuthRpc.Keys?> = runCatching {
        val k = keysObject(ship) ?: return@runCatching null
        val suite = k["suite"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@runCatching null
        val auth = hex32(k["auth"]?.jsonPrimitive?.content) ?: return@runCatching null
        val crypt = hex32(k["crypt"]?.jsonPrimitive?.content) ?: return@runCatching null
        // All-zero keys are a ship that has never set any.
        if (auth.all { it == 0.toByte() } && crypt.all { it == 0.toByte() }) return@runCatching null
        AzimuthRpc.Keys(auth = hex(auth), crypt = hex(crypt), suite = suite)
    }

    /** The `keys` object out of one getPoint, or null when the point has none. */
    private suspend fun keysObject(ship: String): JsonObject? {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", "talon-nym")
            put("method", "getPoint")
            put("params", buildJsonObject { put("ship", ship) })
        }
        val resp = http.post("${baseUrl.trimEnd('/')}/v1/azimuth") {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        // A 404 is the agent saying "no such point": an answer. Any
        // other non-success is the request failing, and throws.
        if (resp.status.value == 404) return null
        if (!resp.status.isSuccess()) error("azimuth-rpc ${resp.status.value}")
        return json.parseToJsonElement(resp.bodyAsText())
            .jsonObject["result"]?.jsonObject
            ?.get("network")?.jsonObject
            ?.get("keys")?.jsonObject
    }

    internal companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** The `keys` object as %azimuth-rpc renders it: `auth` and
         *  `crypt` are 32-byte hex, most significant byte first, and
         *  `suite` says whether they mean anything. */
        fun fingerprintOf(keys: JsonObject): ByteArray? {
            val suite = keys["suite"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
            val auth = hex32(keys["auth"]?.jsonPrimitive?.content) ?: return null
            val crypt = hex32(keys["crypt"]?.jsonPrimitive?.content) ?: return null
            return AzimuthFingerprint.of(auth, crypt, suite)
        }

        /** A key back as hex, the way Azimuth renders one. */
        internal fun hex(bytes: ByteArray): String =
            "0x" + bytes.joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') }

        /** A 32-byte key from hex, left-padded: a key with leading
         *  zero bytes is still a 32-byte key. */
        internal fun hex32(s: String?): ByteArray? {
            val h = (s ?: return null).removePrefix("0x").replace(".", "")
            if (h.isEmpty() || h.length > 64 || h.any { it.digitToIntOrNull(16) == null }) return null
            val padded = h.padStart(64, '0')
            return ByteArray(32) { padded.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
    }
}

/**
 * A ship's public keys as one block to hand to somebody: who they
 * belong to, and both keys. Azimuth already publishes these; this is
 * the copy a person can paste into a message.
 */
fun shipKeyBlock(ship: String, keys: AzimuthRpc.Keys): String = buildString {
    append(ship).append('\n')
    append("signing: ").append(keys.auth).append('\n')
    append("encryption: ").append(keys.crypt).append('\n')
    append("suite: ").append(keys.suite)
}
