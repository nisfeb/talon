package io.nisfeb.talon.urbit

import kotlinx.coroutines.Dispatchers
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.atomic.AtomicLong
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
    private val http: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val ship: String,
) {
    private val channelId = "${System.currentTimeMillis()}-${Random.nextLong().toString(16).take(8)}"
    private val channelUrl: HttpUrl = baseUrl.newBuilder()
        .addPathSegments("~/channel/$channelId")
        .build()

    private val nextId = AtomicLong(1)
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
        val request = Request.Builder()
            .url(channelUrl)
            .header("Accept", "text/event-stream")
            .build()
        val listener = object : EventSourceListener() {
            override fun onEvent(source: EventSource, id: String?, type: String?, data: String) {
                val element = runCatching { json.parseToJsonElement(data) }.getOrNull() ?: return
                // inbox is UNLIMITED so trySend only fails after close,
                // at which point the whole flow is shutting down anyway.
                inbox.trySend(UrbitEvent(id?.toLongOrNull(), element))
            }
            override fun onFailure(source: EventSource, t: Throwable?, response: okhttp3.Response?) {
                inbox.close(t ?: RuntimeException("SSE closed: ${response?.code}"))
            }
            override fun onClosed(source: EventSource) { inbox.close() }
        }
        val es = EventSources.createFactory(http).newEventSource(request, listener)
        // Drain the inbox into the channelFlow's send slot. Suspends when
        // the collector is slow, but inbox is unlimited so the SSE callback
        // never has to block on OkHttp's dispatcher thread.
        val forwarder = launch {
            for (event in inbox) send(event)
            close()
        }
        awaitClose {
            // es.cancel() stops new events at the SSE source.
            // inbox.close() terminates the forwarder's for-loop.
            // forwarder.cancel() is belt-and-suspenders: if the
            // forwarder is mid-send when the collector vanishes,
            // channelFlow's send() throws CancellationException
            // anyway and the loop unwinds naturally. The explicit
            // cancel here covers the narrow race where send was
            // about to be invoked. A double-deliver of one event
            // is theoretically possible, but every Urbit event we
            // ingest is idempotent (Room upserts on primary key),
            // so harmless in practice.
            es.cancel()
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
    ): JsonElement = withContext(Dispatchers.IO) {
        val url = baseUrl.newBuilder()
            .addPathSegments("spider/$desk/$inputMark/$threadName/$outputMark.json")
            .build()
        val request = Request.Builder()
            .url(url)
            .put(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("thread $threadName: HTTP ${resp.code}")
            val text = resp.body?.string().orEmpty()
            if (text.isBlank()) JsonNull else json.parseToJsonElement(text)
        }
    }

    /**
     * Synchronous scry — reads a noun path without subscribing.
     *
     * [timeoutSecs] caps the *OkHttp* call duration. Defaults to
     * [RPC_TIMEOUT_SECS] for the bootstrap / poke paths that can
     * legitimately take a few seconds on a slow ship; chat-refresh
     * probes pass a tighter value because they iterate up to 20
     * shape-fallback paths and any single one being slow blocks the
     * loading indicator. A coroutine-level [withTimeout] doesn't help
     * here — OkHttp's `execute()` is blocking and only `call.cancel()`
     * (which the per-call timeout triggers internally) actually
     * unwinds an in-flight request.
     */
    suspend fun scry(
        app: String,
        path: String,
        timeoutSecs: Long = RPC_TIMEOUT_SECS,
    ): JsonElement =
        withContext(Dispatchers.IO) {
            val url = baseUrl.newBuilder()
                .addPathSegments("~/scry/$app$path.json")
                .build()
            val request = Request.Builder().url(url).get().build()
            http.newCall(request).withRpcTimeout(timeoutSecs).execute().use { resp ->
                if (!resp.isSuccessful) error("scry $app$path: HTTP ${resp.code}")
                val body = resp.body?.string() ?: error("empty scry body")
                json.parseToJsonElement(body)
            }
        }

    /** PUT a batch of channel actions. Runs on Dispatchers.IO. */
    private suspend fun put(messages: JsonArray) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(channelUrl)
            .put(messages.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        http.newCall(request).withRpcTimeout().execute().use { resp ->
            if (!resp.isSuccessful) error("channel PUT: HTTP ${resp.code}")
        }
    }

    /**
     * Cap one RPC call at [timeoutSecs]. The shared OkHttpClient uses
     * readTimeout=0 so the SSE channel can stay open indefinitely;
     * without a per-call cap, a hung scry or poke would wait forever
     * (e.g. when the ship returns intermittent 504s on overload).
     */
    private fun okhttp3.Call.withRpcTimeout(
        timeoutSecs: Long = RPC_TIMEOUT_SECS,
    ): okhttp3.Call = apply {
        timeout().timeout(timeoutSecs, java.util.concurrent.TimeUnit.SECONDS)
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val RPC_TIMEOUT_SECS = 30L
    }
}

/** Raw SSE event: optional sequence id from the server, plus JSON payload. */
data class UrbitEvent(val id: Long?, val body: JsonElement)
