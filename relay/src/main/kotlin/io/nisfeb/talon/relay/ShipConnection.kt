package io.nisfeb.talon.relay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Per-ship SSE consumer. The relay opens one of these for every
 * unique (shipUrl, cookie) pair on startup and dispatches FCM
 * pushes for every `%activity` event that lands on a non-muted
 * whom we haven't already notified the device about, and for every
 * incoming %trunk ring.
 *
 * Reconnect strategy: if the SSE event source closes for any
 * reason, we wait with exponential backoff (capped at 60s) and
 * re-open. A successful event resets the backoff. The relay is
 * supposed to be the layer that "always finds a way" — drop nothing.
 *
 * Dedup: we record [Db.setLastEventId] for the per-(ship, device)
 * cursor on every successful push; on relay restart, we resume from
 * that cursor instead of "now," which prevents losing events the
 * relay was holding when it crashed.
 */
class ShipConnection(
    private val shipRowId: Long,
    private val shipUrl: String,
    private val cookie: String,
    private val deviceId: String,
    private val patp: String,
    private val db: Db,
    private val push: Push,
    private val http: OkHttpClient,
    /** Tunables for the cold-start warmup + freshness filter. Defaults
     *  in [SuppressionConfig] match what the relay ships with; tests
     *  inject shorter windows so they don't sleep for minutes. */
    private val suppression: SuppressionConfig = SuppressionConfig(),
) {

    private val log = LoggerFactory.getLogger("ShipConn[$patp/${deviceId.take(6)}]")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val factory by lazy { EventSources.createFactory(http) }
    private var sourceJob: Job? = null
    private val ackedIds = ConcurrentHashMap.newKeySet<String>()

    fun start() {
        sourceJob = scope.launch {
            var backoffMs = 1_000L
            while (isActive) {
                val ok = runConnection()
                if (ok) backoffMs = 1_000L
                else {
                    log.warn("SSE dropped; reconnecting in ${backoffMs}ms")
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
                }
            }
        }
    }

    fun stop() {
        sourceJob?.cancel()
        scope.cancel()
    }

    /** Returns true if we exited via cancellation (operator-driven),
     *  false on any other failure path so the caller backs off. */
    private suspend fun runConnection(): Boolean {
        val channelId = openChannel() ?: return false
        val subscribed = subscribe(channelId)
        if (!subscribed) return false
        // Per-connection state for the suppression decision (see
        // [decideSuppress]). Captured into the handler closure so
        // each fresh SSE connection starts a new warmup window.
        val connStartMs = System.currentTimeMillis()
        val isFirstConnect = db.lastEventId(shipRowId, deviceId) == null
        return consumeEvents(channelId, connStartMs, isFirstConnect)
    }

    /** Open a `/~/channel/<id>` and return its id. Cookie auth via
     *  the urbauth-~patp header we got from /~/login at registration. */
    private fun openChannel(): String? {
        val channelId = UUID.randomUUID().toString().replace("-", "")
        // Urbit channels are auto-created on first poke. We touch
        // them with an empty PUT to /~/channel/<id> to materialize.
        // (Tlon's webapp does the same — sends a no-op poke first.)
        val req = Request.Builder()
            .url("$shipUrl/~/channel/$channelId")
            .put("[]".toRequestBody(JSON_MEDIA))
            .header("Cookie", cookie)
            .build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) channelId else {
                    log.warn("openChannel failed: ${resp.code}")
                    null
                }
            }
        }.getOrNull()
    }

    private fun subscribe(channelId: String): Boolean {
        // %activity /v4 — same subscribe path Talon-the-app uses — plus
        // %trunk /calls for rings. Most ships have no %trunk installed;
        // eyre nacks that one subscription individually and the channel
        // (and %activity with it) carries on, so a missing desk costs
        // nothing but a logged warning.
        val ship = patp.removePrefix("~")
        val payload =
            """[{"id":$SUB_ACTIVITY,"action":"subscribe","ship":"$ship","app":"activity","path":"/v4"},""" +
                """{"id":$SUB_CALLS,"action":"subscribe","ship":"$ship","app":"trunk","path":"/calls"}]"""
        val req = Request.Builder()
            .url("$shipUrl/~/channel/$channelId")
            .put(payload.toRequestBody(JSON_MEDIA))
            .header("Cookie", cookie)
            .build()
        return runCatching {
            http.newCall(req).execute().use { resp -> resp.isSuccessful }
        }.getOrDefault(false)
    }

    private suspend fun consumeEvents(
        channelId: String,
        connStartMs: Long,
        isFirstConnect: Boolean,
    ): Boolean {
        val req = Request.Builder()
            .url("$shipUrl/~/channel/$channelId")
            .header("Cookie", cookie)
            .header("Accept", "text/event-stream")
            .build()

        val gate = Mutex(locked = true)
        var clean = false
        val source: EventSource = factory.newEventSource(
            req,
            object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: okhttp3.Response) {
                    log.info(
                        "SSE open (firstConnect=$isFirstConnect, warmup=${
                            if (isFirstConnect) suppression.firstConnectWarmupMs
                            else suppression.reconnectWarmupMs
                        }ms)",
                    )
                }

                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String,
                ) {
                    val parsed = runCatching { Json.parseToJsonElement(data).jsonObject }
                        .getOrNull() ?: return
                    handleEvent(id, parsed, channelId, connStartMs, isFirstConnect)
                }

                override fun onClosed(eventSource: EventSource) {
                    log.info("SSE closed by server")
                    if (gate.isLocked) gate.unlock()
                }

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: okhttp3.Response?,
                ) {
                    log.warn("SSE failure: ${t?.message ?: response?.code}")
                    if (gate.isLocked) gate.unlock()
                }
            },
        )
        return try {
            gate.withLock { /* unlocked by onClosed/onFailure */ }
            clean
        } finally {
            source.cancel()
        }
    }

    private fun handleEvent(
        eventId: String?,
        body: JsonObject,
        channelId: String,
        connStartMs: Long,
        isFirstConnect: Boolean,
    ) {
        // Ack the SSE event so the channel doesn't backfill on reconnect.
        eventId?.let { ackEvent(channelId, it) }

        // Tlon wraps events as {response, json: {...inner...}} on the
        // "channel" wire shape. Most events on the stream aren't
        // notification-triggers (activity-summary diffs, ack frames,
        // muted-thread updates) — silent early-return is intentional.
        val response = body["response"]?.jsonPrimitive?.contentOrNull
        if (response != "diff" && response != "subscribe") return

        // A nacked subscribe carries `err` and no `json`. The %trunk one
        // nacks on any ship without the desk, which is the common case.
        body["err"]?.jsonPrimitive?.contentOrNull?.let { err ->
            log.warn("subscription ${body["id"]?.jsonPrimitive?.contentOrNull} refused: $err")
            return
        }
        val json = body["json"]?.jsonObject ?: return

        // Route by subscription id — the two streams have nothing in
        // common beyond the channel they share.
        if (body["id"]?.jsonPrimitive?.contentOrNull == SUB_CALLS.toString()) {
            handleRing(json)
            return
        }

        // %activity /v4 envelope shape:
        //   { add: { source: { dm | club | channel: {...} },
        //            event: { notified: bool, dm-post|chan-post: {...} } } }
        // The `notified` flag lives inside `event`, not on `add` itself
        // — earlier revisions read `add.notified` and silently dropped
        // every real notification because that field doesn't exist.
        val add = json["add"]?.jsonObject ?: return
        val sourceObj = add["source"]?.jsonObject ?: return
        val whom = extractWhom(sourceObj) ?: return
        val event = add["event"]?.jsonObject ?: return
        val notify = (event["notified"] as? JsonPrimitive)?.booleanOrNull == true
        if (!notify) return
        // Globally-unique post id. dm-post / chan-post / club-post
        // all wrap the same key.id shape: "<author>/<128-bit-id>".
        // Using this as the dedup cursor instead of the SSE event id
        // means a reconnect-and-replay won't re-push events we
        // already delivered (the SSE event id resets to 1 on every
        // channel open).
        val postId = extractPostId(event) ?: return

        val cursor = db.lastEventId(shipRowId, deviceId)
        if (cursor == postId) return

        // Suppression layer — see Suppression.kt for the full
        // contract. Cursor still advances on suppress so we don't
        // re-evaluate the same event on the next reconnect.
        val nowMs = System.currentTimeMillis()
        val eventTimeMs = UrbitTime.postIdToMs(postId)
        val decision = decideSuppress(
            nowMs = nowMs,
            connStartMs = connStartMs,
            isFirstConnect = isFirstConnect,
            eventTimeMs = eventTimeMs,
            config = suppression,
        )
        if (decision != SuppressReason.NONE) {
            log.info(
                "suppress ($decision) post=$postId age=${
                    eventTimeMs?.let { nowMs - it }?.toString() ?: "?"
                }ms",
            )
            db.setLastEventId(shipRowId, deviceId, postId)
            return
        }

        val pushEndpoint = db.pushEndpointFor(deviceId) ?: run {
            log.warn("device $deviceId has no push endpoint; skipping")
            return
        }
        log.info("push whom=$whom post=$postId")
        push.send(endpoint = pushEndpoint, patp = patp, whom = whom, postId = postId)
        db.setLastEventId(shipRowId, deviceId, postId)
    }

    /**
     * An incoming 1:1 signal. Only a ring wakes the device: the rest of
     * the exchange (offer / accept / hangup) is for a client that is
     * already awake and subscribed.
     *
     * Deliberately outside the suppression layer. Warmup exists to
     * swallow %activity's subscribe-time backlog, and a ring has no
     * equivalent — %trunk sends nothing on subscribe, and every relay
     * connection opens a brand-new channel, so there is no replay to
     * damp. A ring reaching us is always live, and suppressing it for
     * 30s after a reconnect would simply mean a missed call.
     *
     * It also stays off the %activity cursor: that cursor resumes the
     * message stream after a crash, and a call is not a message.
     */
    private fun handleRing(json: JsonObject) {
        val recv = json["recv"]?.jsonObject ?: return
        val from = recv["from"]?.jsonPrimitive?.contentOrNull ?: return
        val callId = recv["sig"]?.jsonObject
            ?.get("ring")?.jsonObject
            ?.get("id")?.jsonPrimitive?.contentOrNull
            ?: return

        val pushEndpoint = db.pushEndpointFor(deviceId) ?: run {
            log.warn("device $deviceId has no push endpoint; dropping ring")
            return
        }
        log.info("push ring from=$from call=$callId")
        push.sendRing(endpoint = pushEndpoint, patp = patp, from = from, callId = callId)
    }

    /** Pull the globally-unique post id out of an %activity event.
     *  The Tlon agent wraps it in dm-post / chan-post / club-post
     *  depending on the source kind; all three share `.key.id`. */
    private fun extractPostId(event: JsonObject): String? {
        for (kind in arrayOf("dm-post", "chan-post", "club-post")) {
            event[kind]?.jsonObject?.get("key")
                ?.jsonObject?.get("id")
                ?.jsonPrimitive?.contentOrNull
                ?.let { return it }
        }
        return null
    }

    private fun extractWhom(source: JsonObject): String? {
        // Tlon's source can be { dm: { ship: "~..." } }, { club: { id }},
        // or { channel: { nest: "chat/...", group: "~.../..." } }.
        // We surface a stable string for each.
        source["dm"]?.jsonObject?.get("ship")?.jsonPrimitive?.contentOrNull
            ?.let { return it }
        source["club"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
            ?.let { return it }
        source["channel"]?.jsonObject?.get("nest")?.jsonPrimitive?.contentOrNull
            ?.let { return it }
        return null
    }

    private fun ackEvent(channelId: String, id: String) {
        if (!ackedIds.add(id)) return
        val payload = """[{"id":${id.toLongOrNull() ?: return},"action":"ack","event-id":${id.toLong()}}]"""
        val req = Request.Builder()
            .url("$shipUrl/~/channel/$channelId")
            .put(payload.toRequestBody(JSON_MEDIA))
            .header("Cookie", cookie)
            .build()
        runCatching { http.newCall(req).execute().close() }
    }

    private val JsonPrimitive.contentOrNull: String?
        get() = runCatching { content }.getOrNull()
    private val JsonPrimitive.booleanOrNull: Boolean?
        get() = runCatching { boolean }.getOrNull()

    private companion object {
        /** Subscription ids on the shared eyre channel. */
        const val SUB_ACTIVITY = 1
        const val SUB_CALLS = 2

        private val JSON_MEDIA = "application/json".toMediaType()
    }
}

/**
 * Coordinates [ShipConnection] instances across the relay. One
 * registry per relay process.
 */
class ConnectionPool(
    private val db: Db,
    private val push: Push,
    private val masterSecret: String,
) {

    private val log = LoggerFactory.getLogger("ConnectionPool")
    private val active = ConcurrentHashMap<Long, ShipConnection>()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // Long-lived SSE — no read timeout.
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    fun startAll() {
        for (row in db.allShips()) ensureRunning(row)
    }

    fun stopAll() {
        active.values.forEach { it.stop() }
        active.clear()
    }

    fun ensureRunning(row: Db.ShipRow) {
        if (active.containsKey(row.rowId)) return
        val cookie = Crypto.open(
            Crypto.Sealed(row.ciphertextB64, row.saltB64, row.nonceB64),
            masterSecret,
        ) ?: run {
            log.warn("decrypt failed for ship row ${row.rowId}; skipping")
            return
        }
        val conn = ShipConnection(
            shipRowId = row.rowId,
            shipUrl = row.shipUrl,
            cookie = cookie,
            deviceId = row.deviceId,
            patp = row.patp,
            db = db,
            push = push,
            http = http,
        )
        conn.start()
        active[row.rowId] = conn
        log.info("ship connection started: ${row.patp} for device=${row.deviceId.take(6)}")
    }

    fun stopRow(rowId: Long) {
        active.remove(rowId)?.stop()
    }
}
