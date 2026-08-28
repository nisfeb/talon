package io.nisfeb.talon.urbit

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.readUTF8Line
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.nisfeb.talon.util.ioDispatcher
import io.nisfeb.talon.util.Log
import io.nisfeb.talon.util.nowMs
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random

/**
 * One /~/channel/{uid} connection. Multiplexes subscriptions + pokes over
 * a single SSE stream. Call connect() to get a Flow of wire events; call
 * subscribe/poke/unsubscribe/ack to send messages back up the channel.
 *
 * Events are returned as raw JsonElements — the caller is responsible for
 * typed decoding per-agent.
 */
class UrbitChannel internal constructor(
    private val http: HttpClient,
    private val baseUrl: String,
    private val ship: String,
) {
    private val channelId = "${nowMs()}-${Random.nextLong().toString(16).take(8)}"
    private val channelUrl: String = "${baseUrl.trimEnd('/')}/~/channel/$channelId"

    private val nextId = atomic(1L)
    private val json = Json { ignoreUnknownKeys = true }

    /** Unique message id generator. Urbit requires monotonically-increasing ids. */
    private fun nextRequestId(): Long = nextId.getAndIncrement()

    // Pokes awaiting their ack, by request id. Eyre answers every poke
    // on the SSE stream with {"id":n,"response":"poke","ok"|"err"}, so
    // the forwarder below can settle them.
    private val pendingPokes = mutableMapOf<Long, CompletableDeferred<String?>>()
    private val pokeLock = Mutex()

    /**
     * Settle a poke waiting on its ack. Called for every inbound event.
     *
     * Failing to find an id is normal and silent: facts, subscription
     * responses and acks for pokes we already gave up on all land here.
     */
    private suspend fun settlePokeAck(element: JsonElement) {
        val obj = element as? JsonObject ?: return
        if (obj["response"]?.jsonPrimitive?.contentOrNull != "poke") return
        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return
        val err = obj["err"]?.jsonPrimitive?.contentOrNull
        pokeLock.withLock { pendingPokes.remove(id) }?.complete(err)
    }

    /**
     * Opens the SSE stream. Hot flow — every collector shares the same
     * connection for the life of this UrbitChannel instance.
     *
     * Events are buffered through an UNLIMITED intermediate Channel so
     * that bursts (e.g. 10 messages delivered back-to-back after a
     * reconnect) never drop events due to downstream backpressure —
     * the DB writes in applyEvent are slow enough that the default
     * rendezvous/buffered channel would shed events under load.
     */
    fun events(): Flow<UrbitEvent> = channelFlow {
        val inbox = Channel<UrbitEvent>(Channel.UNLIMITED)
        // Drive the SSE session on its own coroutine. The sse{} block
        // stays suspended for the life of the connection; when this
        // job is cancelled (awaitClose below) the session closes.
        val reader = launch {
            try {
                // Read the event-stream by hand instead of via Ktor's SSE
                // plugin. The plugin left eyre holding the channel GET open
                // but never flushing facts — the field symptom was the 90s
                // watchdog force-reconnecting forever with zero events,
                // which stranded every optimistic grey post (its echo never
                // arrived to reap it) and let the reconnect re-scry re-add
                // the same post as a second row: the duplicate-message bug.
                // A plain streaming GET with `Accept-Encoding: identity`
                // makes eyre emit each frame uncompressed the instant it's
                // ready; parsing frames ourselves keeps this in commonMain
                // for every platform.
                http.prepareGet(channelUrl) {
                    header(HttpHeaders.Accept, "text/event-stream")
                    header(HttpHeaders.CacheControl, "no-cache")
                    header(HttpHeaders.AcceptEncoding, "identity")
                }.execute { resp ->
                    if (!resp.status.isSuccess()) error("channel SSE: HTTP ${resp.status.value}")
                    val body = resp.bodyAsChannel()
                    var id: Long? = null
                    val data = StringBuilder()
                    while (true) {
                        val line = body.readUTF8Line() ?: break
                        when {
                            // Blank line terminates a frame — dispatch it.
                            line.isEmpty() -> {
                                if (data.isNotEmpty()) {
                                    val element = runCatching { json.parseToJsonElement(data.toString()) }.getOrNull()
                                    // inbox is UNLIMITED so trySend only fails
                                    // after close, when the flow is shutting down.
                                    if (element != null) inbox.trySend(UrbitEvent(id, element))
                                }
                                id = null
                                data.setLength(0)
                            }
                            line.startsWith(":") -> {} // heartbeat / comment
                            line.startsWith("id:") -> id = line.removePrefix("id:").trim().toLongOrNull()
                            line.startsWith("data:") -> {
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(line.removePrefix("data:").removePrefix(" "))
                            }
                        }
                    }
                }
                inbox.close()
            } catch (t: Throwable) {
                inbox.close(t)
            }
        }
        // Drain the inbox into the channelFlow's send slot. Suspends when
        // the collector is slow, but inbox is unlimited so the SSE reader
        // never has to block on the engine's dispatcher.
        val forwarder = launch {
            for (event in inbox) {
                // Settle before forwarding: a collector that is slow to
                // consume must not delay a poke waiting on its ack.
                settlePokeAck(event.body)
                send(event)
            }
            close()
        }
        awaitClose {
            reader.cancel()
            inbox.close()
            forwarder.cancel()
        }
    }

    suspend fun subscribe(app: String, path: String, onShip: String = ship): Long {
        val id = nextRequestId()
        val msg = buildJsonObject {
            put("id", id)
            put("action", "subscribe")
            put("ship", onShip)
            put("app", app)
            put("path", path)
        }
        put(buildJsonArray { add(msg) })
        return id
    }

    suspend fun unsubscribe(subscriptionId: Long) {
        val id = nextRequestId()
        val msg = buildJsonObject {
            put("id", id)
            put("action", "unsubscribe")
            put("subscription", subscriptionId)
        }
        put(buildJsonArray { add(msg) })
    }

    /**
     * Poke an agent and wait for the ship to accept it.
     *
     * This used to PUT and return, never reading the ack, so a nacked
     * poke was indistinguishable from a delivered one. Every failure of
     * that kind surfaced instead as a control that silently did nothing
     * — a mark whose key we had spelled wrong, an agent too old to
     * understand the action — and each one cost a debugging session.
     *
     * A nack now throws. A timeout does not: an ack only arrives if
     * something is collecting [events], and callers that poke without a
     * live stream are not wrong, merely unobserved. Losing the signal
     * there is the old behaviour, so it stays a warning rather than
     * failing work that probably succeeded.
     */
    suspend fun poke(
        app: String,
        mark: String,
        payload: JsonElement,
        onShip: String = ship,
    ): Long {
        val id = nextRequestId()
        val msg = buildJsonObject {
            put("id", id)
            put("action", "poke")
            put("ship", onShip)
            put("app", app)
            put("mark", mark)
            put("json", payload)
        }
        val ack = CompletableDeferred<String?>()
        pokeLock.withLock { pendingPokes[id] = ack }
        try {
            put(buildJsonArray { add(msg) })
        } catch (t: Throwable) {
            pokeLock.withLock { pendingPokes.remove(id) }
            throw t
        }
        val err = withTimeoutOrNull(POKE_ACK_TIMEOUT_MS) { ack.await() }
        if (err == null && !ack.isCompleted) {
            pokeLock.withLock { pendingPokes.remove(id) }
            Log.w(TAG, "no ack for poke $id to $app/$mark within ${POKE_ACK_TIMEOUT_MS}ms")
            return id
        }
        if (err != null) throw PokeNacked(app, mark, err)
        return id
    }

    suspend fun ack(eventId: Long) {
        val id = nextRequestId()
        val msg = buildJsonObject {
            put("id", id)
            put("action", "ack")
            put("event-id", eventId)
        }
        put(buildJsonArray { add(msg) })
    }

    /**
     * Run a spider thread and return its output JSON. Threads are
     * one-shot RPCs that do synchronous work (like creating a group).
     * Path: `/spider/<desk>/<input-mark>/<thread-name>/<output-mark>.json`.
     */
    suspend fun runThread(
        desk: String,
        inputMark: String,
        threadName: String,
        outputMark: String,
        body: JsonElement,
    ): JsonElement = withContext(ioDispatcher) {
        val url = "${baseUrl.trimEnd('/')}/spider/$desk/$inputMark/$threadName/$outputMark.json"
        val resp = http.put(url) {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
            timeout { requestTimeoutMillis = RPC_TIMEOUT_SECS * 1000 }
        }
        if (!resp.status.isSuccess()) error("thread $threadName: HTTP ${resp.status.value}")
        val text = resp.bodyAsText()
        if (text.isBlank()) JsonNull else json.parseToJsonElement(text)
    }

    /**
     * Synchronous scry — reads a noun path without subscribing.
     *
     * [timeoutSecs] caps the per-request duration. Defaults to
     * [RPC_TIMEOUT_SECS] for the bootstrap / poke paths that can
     * legitimately take a few seconds on a slow ship; chat-refresh
     * probes pass a tighter value because they iterate up to 20
     * shape-fallback paths and any single one being slow blocks the
     * loading indicator.
     */
    suspend fun scry(
        app: String,
        path: String,
        timeoutSecs: Long = RPC_TIMEOUT_SECS,
    ): JsonElement = withContext(ioDispatcher) {
        val url = "${baseUrl.trimEnd('/')}/~/scry/$app$path.json"
        val resp = http.get(url) {
            timeout { requestTimeoutMillis = timeoutSecs * 1000 }
        }
        if (!resp.status.isSuccess()) error("scry $app$path: HTTP ${resp.status.value}")
        val body = resp.bodyAsText()
        if (body.isEmpty()) error("empty scry body")
        json.parseToJsonElement(body)
    }

    /**
     * Authenticated JSON request against an agent's own eyre-bound HTTP
     * API (e.g. %notes' `/notes/~/v1/...`).
     *
     * Channel pokes are fire-and-forget: the PUT returns as soon as eyre
     * accepts it, and a nack only shows up later as an SSE poke-ack. That
     * makes them unusable when the caller needs the outcome — an edit
     * rejected for being stale looks identical to one that landed. These
     * REST endpoints answer synchronously with a typed body instead.
     *
     * Returns the parsed response, or throws on a transport/HTTP failure.
     */
    suspend fun apiJson(
        method: String,
        path: String,
        body: JsonElement? = null,
    ): JsonElement = withContext(ioDispatcher) {
        val url = "${baseUrl.trimEnd('/')}$path"
        val resp = http.request(url) {
            this.method = HttpMethod.parse(method)
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            timeout { requestTimeoutMillis = RPC_TIMEOUT_SECS * 1000 }
        }
        if (!resp.status.isSuccess()) error("$method $path: HTTP ${resp.status.value}")
        val text = resp.bodyAsText()
        if (text.isBlank()) JsonNull else json.parseToJsonElement(text)
    }

    /** PUT a batch of channel actions. Runs on the IO dispatcher. */
    private suspend fun put(messages: JsonArray) = withContext(ioDispatcher) {
        val resp = http.put(channelUrl) {
            contentType(ContentType.Application.Json)
            setBody(messages.toString())
            timeout { requestTimeoutMillis = RPC_TIMEOUT_SECS * 1000 }
        }
        if (!resp.status.isSuccess()) error("channel PUT: HTTP ${resp.status.value}")
    }

    companion object {
        private const val RPC_TIMEOUT_SECS = 30L
        private const val TAG = "UrbitChannel"

        /**
         * How long to wait for a poke ack.
         *
         * Generous: this covers a round trip to the ship, not any work
         * the agent does afterwards. Short enough that a caller poking
         * with no live event stream isn't held up for long.
         */
        private const val POKE_ACK_TIMEOUT_MS = 15_000L
    }
}

/** Raw SSE event: optional sequence id from the server, plus JSON payload. */
data class UrbitEvent(val id: Long?, val body: JsonElement)

/**
 * The ship refused a poke.
 *
 * Carries what was refused as well as why: eyre's message is a crash
 * stack, and "bad-key" on its own says nothing about which action was
 * being attempted.
 */
class PokeNacked(
    val app: String,
    val mark: String,
    val reason: String,
) : RuntimeException("$app rejected a $mark poke: $reason")
