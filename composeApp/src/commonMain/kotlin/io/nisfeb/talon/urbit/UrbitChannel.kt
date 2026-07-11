package io.nisfeb.talon.urbit

import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.sse
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.nisfeb.talon.util.ioDispatcher
import io.nisfeb.talon.util.nowMs
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
                http.sse(
                    urlString = channelUrl,
                    request = { header(HttpHeaders.Accept, "text/event-stream") },
                ) {
                    incoming.collect { ev ->
                        val data = ev.data ?: return@collect
                        val element = runCatching { json.parseToJsonElement(data) }.getOrNull()
                            ?: return@collect
                        // inbox is UNLIMITED so trySend only fails after close,
                        // at which point the whole flow is shutting down anyway.
                        inbox.trySend(UrbitEvent(ev.id?.toLongOrNull(), element))
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
            for (event in inbox) send(event)
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
        put(buildJsonArray { add(msg) })
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
    }
}

/** Raw SSE event: optional sequence id from the server, plus JSON payload. */
data class UrbitEvent(val id: Long?, val body: JsonElement)
