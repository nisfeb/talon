package io.nisfeb.talon.urbit

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.concurrent.ConcurrentHashMap

/**
 * A ship for repo tests. Every poke sent to it is recorded and acked on
 * its event stream, or refused where [refuse] names a reason. A scry
 * answers from [scries], keyed by what follows `/~/scry/` without the
 * `.json`, or is a 404.
 *
 * Acks reach a waiting poke only while something collects
 * `channel.events()`, as in the app; without that every poke waits out
 * its ack timeout.
 */
/**
 * [timeouts] gives the client the app's own request timeouts (HttpTimeout):
 * without it a held answer waits forever, so no read here ever times out.
 */
internal class FakeShip(val us: String = "~zod", timeouts: Boolean = false) {
    data class Poke(val app: String, val mark: String, val json: JsonElement, val ship: String)

    val pokes: MutableList<Poke> = java.util.concurrent.CopyOnWriteArrayList()
    val scries = ConcurrentHashMap<String, String>()

    /** Every subscription opened, as `app/path`. */
    val subscribed: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    /** How many subscriptions each PUT that carried any carried. */
    val subscribePuts: MutableList<Int> = java.util.concurrent.CopyOnWriteArrayList()
    /** The event ids each ack named, in order. */
    val acked: MutableList<Long> = java.util.concurrent.CopyOnWriteArrayList()

    /** Every scry path asked for, answered or not, in order. */
    val scried: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    @Volatile var refuse: (Poke) -> String? = { null }

    /**
     * A poke the wire loses before the ship sees it, as a PUT that timed
     * out or a connection that dropped: what the request fails with, or
     * null to deliver it. Lost pokes are in [attempted], not [pokes].
     */
    @Volatile var lose: (Poke) -> Throwable? = { null }

    /**
     * A poke the ship takes and acks whose PUT fails anyway, as on a busy
     * ship that answers after the client gave up: it is in [pokes], and
     * the request fails with this.
     */
    @Volatile var landThenFail: (Poke) -> Throwable? = { null }

    /** A poke the ship takes and never answers, as a wedged flow to a peer: in [pokes], with no ack. */
    @Volatile var silent: (Poke) -> Boolean = { false }

    /** Every poke sent, lost or not. */
    val attempted: MutableList<Poke> = java.util.concurrent.CopyOnWriteArrayList()

    /** Requests to an app's own HTTP API, as `METHOD path body`. */
    val api: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    /** The answer to an API request, or null for 404. */
    @Volatile var answerApi: (method: String, path: String, body: String) -> String? = { _, _, _ -> null }
    /** A scry the ship fails on (500), as a crashed or busy agent does; unlike one it has no answer for (404). */
    @Volatile var failScry: (path: String) -> Boolean = { false }
    /** How long a scry waits before it is answered: a busy ship, by path. */
    @Volatile var holdScry: (path: String) -> Long = { 0 }
    /** A scry that never comes back: the connection dropped, or a busy ship timed out. */
    @Volatile var loseScry: (path: String) -> Throwable? = { null }
    /** How long a poke waits before the ship takes it: a slow ship, at work. */
    @Volatile var holdPoke: Long = 0
    /** How long a channel's delete waits for an answer: a dead socket, as an iOS app finds coming back. */
    @Volatile var holdDelete: Long = 0
    /** The login has lapsed (eyre forgot its cookie): every channel request and scry is a 403, a new channel's too. */
    @Volatile var signedOut: Boolean = false
    /** While true the event stream is refused, as with no network yet. */
    @Volatile var refuseStream: Boolean = false
    /** A subscription refused, by `app/path` (e.g. "activity/v6"): an older ship without it. */
    @Volatile var refuseWatch: (String) -> String? = { null }
    /** A subscription whose PUT is lost on the way, by `app/path`: what the request fails with, or null to deliver it. */
    @Volatile var loseWatch: (String) -> Throwable? = { null }
    /** How long a subscribe's PUT waits to return after its answer is on the stream: eyre's answer can come first. */
    @Volatile var holdWatchAnswer: Long = 0
    /** Every request, "METHOD path", whatever it was for: what an app that must say nothing did say. */
    val requests: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    /** Each event stream asked for: its channel, and the Last-Event-ID it carried. */
    val streamsOpened: MutableList<Pair<String, String?>> = java.util.concurrent.CopyOnWriteArrayList()

    // Channels a PUT made, and those reaped since: a stream on a reaped one is
    // a 404 until a PUT makes it again, as with eyre.
    private val known: MutableSet<String> = ConcurrentHashMap.newKeySet()
    // Channels whose first PUT was lost: eyre never made them, so a stream
    // asked for on one is a 404 until a PUT gets through and makes it.
    private val unmade: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Every channel a delete was sent for, in order. */
    val deleted: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    private val reaped: MutableSet<String> = ConcurrentHashMap.newKeySet()
    // Channels refused to this login since [forbid]: a 403 on stream and PUT.
    private val forbidden: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The request id each watch (app + path) was last made with, for [quit]. */
    val subIds = ConcurrentHashMap<String, Long>()

    /**
     * The calendar app at /apps/calendar, as its parser in calendar's
     * app.hoon answers. Reads answer from these fields; writes (pokes
     * through the grubbery shell, and answers to invitations) are kept in
     * order and taken unless refused.
     */
    class FakeCalendar {
        /** window.json's rows, a JSON array; every window gets them all. */
        @Volatile var rows = "[]"
        @Volatile var calendars = """[{"id":"default","name":"Personal","kind":"local"}]"""
        @Volatile var zones = """["UTC"]"""
        /** events.json: the task listing, a JSON array. */
        @Volatile var tasks = "[]"
        /** event.json, by id; one not here is a 404. */
        val events = ConcurrentHashMap<String, String>()
        /** invites.json; null for a calendar older than invitations (34), which answers 404. */
        @Volatile var invites: String? = "[]"
        /** invites.json fails (500), as a busy or crashed nexus does: not the same as a 404. */
        @Volatile var invitesFail = false
        /** Every poke's body, in order. */
        val pokes: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
        /** Every POST to invites/answer, its body, in order. */
        val answers: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
        /** A poke the shell refuses (400), by its body: one it cannot read at all. */
        @Volatile var refusePoke: (String) -> Boolean = { false }
        /**
         * Why the calendar refuses a poke it has taken, by its body, or
         * null to keep it: the calendar's own refusals. It answers 200,
         * as the ship acks a poke before reading it, and keeps the
         * refusal in outcomes.json (app.hoon's refuse, calendar-core's
         * refusal-put): the poke's own id, why, and the ship's clock.
         */
        @Volatile var refuseLater: (String) -> String? = { null }
        /** outcomes.json's refusals, oldest first, as the calendar keeps them. */
        val refusals: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
        /** outcomes.json is asked for. */
        val outcomesRead = java.util.concurrent.atomic.AtomicInteger()
        /** What a poke the shell took does to the calendar: the ship's own work, for what reads it back. */
        @Volatile var onPoke: FakeCalendar.(String) -> Unit = {}
        /** invites/answer's answer to a body, or null for the calendar's 404. */
        @Volatile var answer: FakeCalendar.(String) -> String? = { """{"ok":true,"cal":"default","told":true}""" }
        /** Writes never reach the ship: each fails as a dropped connection does. */
        @Volatile var lose = false
        /** Writes wait for this before the ship sees them: a busy ship, until it completes. */
        @Volatile var hold: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    }

    /** The ship's calendar; null for none, every read of it a 404. */
    @Volatile var calendar: FakeCalendar? = null

    /** Vere's own /~_~/healthz: 204 idle, 429 busy, a proxy's 502 down. */
    @Volatile var health: Int = 204

    /** A scry with no entry in [scries] is answered by this, for paths that carry a time. */
    @Volatile var answerScry: (path: String) -> String? = { null }

    // One event stream per channel, as eyre keeps one per channel: the app
    // runs several (the repo's, the call controller's), and two channels
    // reading one stream steal each other's bytes. Readers of the SAME
    // channel share its stream. Frames wait in an unbounded queue, pumped
    // into the stream, so a channel nobody reads never blocks a fact meant
    // for the others.
    private class Pipe(val stream: ByteChannel, val queue: Channel<String>)
    private val pipes = LinkedHashMap<String, Pipe>()
    /** Facts put out before any channel opened, for the first to open. */
    private val backlog = mutableListOf<String>()
    private val pumps = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var nextEventId = 1L

    // Pokes arrive on concurrent requests; frames are numbered and queued one
    // at a time, or two acks interleave, one is lost, and its poke waits it out.
    private val writing = Mutex()

    private fun frame(json: String) = "id: ${nextEventId++}\ndata: $json\n\n"

    /** The pipe of the channel at [path], made on first use. Called under [writing]. */
    private fun pipeOf(path: String): Pipe =
        pipes.getOrPut(path.removePrefix("/~/channel/")) {
            Pipe(ByteChannel(autoFlush = true), Channel(Channel.UNLIMITED)).also { p ->
                backlog.forEach { p.queue.trySend(it) }
                backlog.clear()
                // A reader that went away closes the stream; that channel is
                // done, and saying so as an uncaught error fails a later test.
                pumps.launch { runCatching { for (f in p.queue) p.stream.writeStringUtf8(f) } }
            }
        }

    /**
     * The stream for a GET on the channel at [path]. One whose last reader
     * went away is closed; a stream opened on that channel again gets a new
     * one fed from the same queue, as eyre resumes a channel. Called under [writing].
     */
    private fun streamOf(path: String): ByteChannel {
        val id = path.removePrefix("/~/channel/")
        val old = pipes[id] ?: return pipeOf(path).stream
        if (!old.stream.isClosedForWrite) return old.stream
        // A new queue, with what the old one had not written: the old pump
        // still waits on its queue and would take the next frame to a
        // stream nobody reads.
        val fresh = Pipe(ByteChannel(autoFlush = true), Channel(Channel.UNLIMITED))
        while (true) fresh.queue.trySend(old.queue.tryReceive().getOrNull() ?: break)
        old.queue.close()
        pipes[id] = fresh
        pumps.launch { runCatching { for (f in fresh.queue) fresh.stream.writeStringUtf8(f) } }
        return fresh.stream
    }

    /** The id of the last event framed so far. */
    val lastEventId: Long get() = nextEventId - 1

    /**
     * Eyre's keepalive on every open event stream, a bare `:` line each
     * [everyMs], as a real ship sends one every ~25 s news or not. Off
     * unless asked for: most tests read the stream for its facts alone.
     */
    fun heartbeat(everyMs: Long) {
        pumps.launch {
            while (true) {
                kotlinx.coroutines.delay(everyMs)
                writing.withLock { pipes.values.forEach { it.queue.trySend(":\n\n") } }
            }
        }
    }

    /** End every open event stream, as a dropped connection does. */
    suspend fun endStreams() = writing.withLock {
        pipes.values.forEach { it.queue.close(); it.stream.close() }
        pipes.clear()
    }

    /** Forget every channel, as eyre reaps one twelve hours after its stream: streams end, and a channel asked for again is a 404. */
    suspend fun reap() = writing.withLock {
        reaped += known
        pipes.values.forEach { it.queue.close(); it.stream.close() }
        pipes.clear()
    }

    /** Refuse every channel made so far, as eyre does one made by another login: 403 on stream and PUT. */
    suspend fun forbid() = writing.withLock {
        forbidden += known
        pipes.values.forEach { it.queue.close(); it.stream.close() }
        pipes.clear()
    }

    /** Drop the watch [watch] (app + path), as gall's kick reaches a channel: eyre's quit, on its id. */
    suspend fun quit(watch: String) = emit("""{"id":${subIds.getValue(watch)},"response":"quit"}""")

    /** Put a fact on every channel's event stream, framed as eyre frames it. */
    suspend fun emit(json: String) = writing.withLock {
        val f = frame(json)
        if (pipes.isEmpty()) backlog += f else pipes.values.forEach { it.queue.trySend(f) }
    }

    /** An answer for the one channel that asked: a poke's or a subscription's ack. */
    private suspend fun answer(path: String, json: String) = writing.withLock { pipeOf(path).queue.trySend(frame(json)) }

    /** What the app talks to the ship through; pass it as the app's client. */
    val http = HttpClient(MockEngine { req ->
        val path = req.url.encodedPath
        requests += "${req.method.value} $path"
        when {
            signedOut && (path.startsWith("/~/channel/") || path.startsWith("/~/scry/")) -> {
                if (req.method == HttpMethod.Get && path.startsWith("/~/channel/")) streamsOpened += path.removePrefix("/~/channel/") to req.headers["Last-Event-ID"]
                respond("", HttpStatusCode.Forbidden)
            }
            (req.method == HttpMethod.Put || req.method == HttpMethod.Get) && path.startsWith("/~/channel/") &&
                path.removePrefix("/~/channel/") in forbidden -> {
                if (req.method == HttpMethod.Get) streamsOpened += path.removePrefix("/~/channel/") to req.headers["Last-Event-ID"]
                respond("", HttpStatusCode.Forbidden)
            }
            req.method == HttpMethod.Put && path.startsWith("/~/channel/") -> {
                val cid = path.removePrefix("/~/channel/")
                val first = cid !in known
                known += cid; reaped -= cid
                val batch = Json.parseToJsonElement(req.body.toByteArray().decodeToString()).jsonArray
                batch.count { it.jsonObject["action"]?.jsonPrimitive?.content == "subscribe" }.takeIf { it > 0 }?.let { subscribePuts += it }
                try { for (msg in batch) {
                    val o = msg.jsonObject
                    val id = o["id"]!!.jsonPrimitive.long
                    when (o["action"]?.jsonPrimitive?.content) {
                        "poke" -> {
                            if (holdPoke > 0) kotlinx.coroutines.delay(holdPoke)
                            val p = Poke(
                                o["app"]!!.jsonPrimitive.content,
                                o["mark"]!!.jsonPrimitive.content,
                                o["json"]!!,
                                o["ship"]!!.jsonPrimitive.content,
                            )
                            attempted += p
                            lose(p)?.let { throw it }
                            pokes += p
                            val err = refuse(p)
                            if (!silent(p)) answer(
                                path,
                                if (err == null) """{"id":$id,"response":"poke","ok":"ok"}"""
                                else """{"id":$id,"response":"poke","err":${JsonPrimitive(err)}}""",
                            )
                            landThenFail(p)?.let { throw it }
                        }
                        "ack" -> { o["event-id"]?.jsonPrimitive?.content?.toLongOrNull()?.let { acked += it } }
                        "subscribe" -> {
                            val watch = "${o["app"]?.jsonPrimitive?.content}${o["path"]?.jsonPrimitive?.content}"
                            loseWatch(watch)?.let { throw it }
                            subscribed += watch
                            subIds[watch] = id
                            val err = refuseWatch(watch)
                            answer(
                                path,
                                if (err == null) """{"id":$id,"response":"subscribe","ok":"ok"}"""
                                else """{"id":$id,"response":"subscribe","err":${JsonPrimitive(err)}}""",
                            )
                            if (holdWatchAnswer > 0) kotlinx.coroutines.delay(holdWatchAnswer)
                        }
                        "delete" -> { deleted += cid; if (holdDelete > 0) kotlinx.coroutines.delay(holdDelete) }
                    }
                } } catch (t: Throwable) {
                    if (first) { known -= cid; unmade += cid }
                    throw t
                }
                unmade -= cid
                respond("", HttpStatusCode.NoContent)
            }
            req.method == HttpMethod.Get && path.startsWith("/~/channel/") &&
                path.removePrefix("/~/channel/").also { streamsOpened += it to req.headers["Last-Event-ID"] }.let { it in reaped || it in unmade } ->
                respond("", HttpStatusCode.NotFound)
            req.method == HttpMethod.Get && path.startsWith("/~/channel/") && refuseStream ->
                respond("", HttpStatusCode.ServiceUnavailable)
            // Checked again under the lock: a reap or a forbid between the
            // checks above and here would otherwise leave a stream open on
            // a channel that is gone.
            req.method == HttpMethod.Get && path.startsWith("/~/channel/") -> {
                val id = path.removePrefix("/~/channel/")
                when (val stream = writing.withLock { if (id in reaped || id in forbidden) null else streamOf(path) }) {
                    null -> respond("", if (id in forbidden) HttpStatusCode.Forbidden else HttpStatusCode.NotFound)
                    else -> respond(stream, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
                }
            }
            req.method == HttpMethod.Get && path == "/~_~/healthz" -> respond("", HttpStatusCode.fromValue(health))
            path.startsWith("/~/scry/") && loseScry(path.removePrefix("/~/scry/").removeSuffix(".json")) != null -> {
                val asked = path.removePrefix("/~/scry/").removeSuffix(".json")
                scried += asked
                throw loseScry(asked)!!
            }
            path.startsWith("/~/scry/") && failScry(path.removePrefix("/~/scry/").removeSuffix(".json")) ->
                respond("", HttpStatusCode.InternalServerError)
            path.startsWith("/~/scry/") ->
                path.removePrefix("/~/scry/").removeSuffix(".json").also { scried += it }
                    .also { p -> holdScry(p).takeIf { it > 0 }?.let { kotlinx.coroutines.delay(it) } }
                    .let { scries[it] ?: answerScry(it) }
                    ?.let { respond(it, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
                    ?: respond("", HttpStatusCode.NotFound)
            calendar != null && (path.startsWith("/apps/calendar/") || path.endsWith("/calendar.calendar")) -> {
                val c = calendar!!
                val body = req.body.toByteArray().decodeToString()
                val json = { text: String -> respond(text, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
                if (req.method != HttpMethod.Get) {
                    if (c.lose) throw java.io.IOException("connection reset")
                    c.hold?.await()
                }
                when (path.removePrefix("/apps/calendar")) {
                    "/window.json" -> json("""{"rows":${c.rows}}""")
                    "/calendars.json" -> json(c.calendars)
                    "/events.json" -> json(c.tasks)
                    "/tags.json", "/google/conflicts.json", "/caldav/subscriptions.json" -> json("[]")
                    "/config.json" -> json("""{"title":"Calendar","zone":"UTC","ball":"cal"}""")
                    "/zones.json" -> json(c.zones)
                    "/google.json" -> json("""{"connected":true,"linked":{}}""")
                    "/event.json" -> c.events[req.url.parameters["id"]]?.let(json) ?: respond("", HttpStatusCode.NotFound)
                    "/invites.json" -> if (c.invitesFail) respond("", HttpStatusCode.InternalServerError) else c.invites?.let(json) ?: respond("", HttpStatusCode.NotFound)
                    "/invites/answer" -> { c.answers += body; c.answer(c, body)?.let(json) ?: respond("", HttpStatusCode.NotFound) }
                    "/outcomes.json" -> { c.outcomesRead.incrementAndGet(); json("""{"refusals":[${c.refusals.joinToString(",")}],"faults":{}}""") }
                    else -> if (!path.endsWith("/calendar.calendar")) respond("", HttpStatusCode.NotFound) else {
                        c.pokes += body
                        val why = c.refuseLater(body)
                        when {
                            c.refusePoke(body) -> respond("", HttpStatusCode.BadRequest)
                            why != null -> {
                                val poke = Json.parseToJsonElement(body).jsonObject
                                // Kept at once, not a beat after the ack as on the
                                // ship: the app's scope runs on the test's virtual
                                // clock, so its wait to read is no wait in real
                                // time. AssistantActionsToolsTest proves the wait.
                                c.refusals += buildJsonObject {
                                    put("at_ms", System.currentTimeMillis())
                                    put("action", poke["action"]?.jsonPrimitive?.content ?: "")
                                    put("id", poke["id"]?.jsonPrimitive?.content ?: "")
                                    put("from", "")
                                    put("why", why)
                                }.toString()
                                respond("", HttpStatusCode.OK)
                            }
                            else -> { c.onPoke(c, body); respond("", HttpStatusCode.OK) }
                        }
                    }
                }
            }
            // An app's own HTTP API (%notes' v1, orrery's reads, …): recorded, and answered by [answerApi].
            req.method != HttpMethod.Get || path.startsWith("/notes/") || path.startsWith("/apps/orrery/") -> {
                val body = req.body.toByteArray().decodeToString()
                api += "${req.method.value} $path $body"
                answerApi(req.method.value, path, body)
                    ?.let { respond(it, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
                    ?: respond("", HttpStatusCode.NotFound)
            }
            else -> respond("", HttpStatusCode.NotFound)
        }
    }) { if (timeouts) install(io.ktor.client.plugins.HttpTimeout) }

    /** Signed in to this ship at https://ship.test, and nothing else. */
    val session = object : SessionStore {
        private val s = SavedSession("https://ship.test", us, "test-session", "0v1", "ship.test")
        override fun all() = listOf(s)
        override fun active() = s
        override fun activeShip() = s.ship
        override fun save(entry: SavedSession, makeActive: Boolean) {}
        override fun setActive(ship: String) {}
        override fun remove(ship: String) {}
        override fun clearAll() {}
    }

    val channel: UrbitChannel = UrbitSession(http, session).apply { tryRestore(us) }.openChannel()

    /** The pokes made to [app], in order. */
    fun pokesTo(app: String): List<Poke> = synchronized(pokes) { pokes.filter { it.app == app } }

    /**
     * %settings' desk `talon` as a ship that took every settings poke so
     * far holds it, over [base]: a bucket stays, emptied, after its last
     * entry goes. What another device's bootstrap then reads.
     */
    fun settingsDesk(base: String = """{"desk":{}}"""): String {
        val desk = linkedMapOf<String, LinkedHashMap<String, JsonElement>>()
        Json.parseToJsonElement(base).jsonObject["desk"]!!.jsonObject.forEach { (b, m) -> desk[b] = LinkedHashMap(m.jsonObject) }
        fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
        for (p in pokesTo("settings")) {
            val o = p.json.jsonObject
            o["put-entry"]?.jsonObject?.let { e -> desk.getOrPut(e.str("bucket-key")) { linkedMapOf() }[e.str("entry-key")] = e["value"]!! }
            o["del-entry"]?.jsonObject?.let { e -> desk[e.str("bucket-key")]?.remove(e.str("entry-key")) }
            o["put-bucket"]?.jsonObject?.let { e -> desk[e.str("bucket-key")] = LinkedHashMap(e["bucket"]!!.jsonObject) }
            o["del-bucket"]?.jsonObject?.let { e -> desk.remove(e.str("bucket-key")) }
        }
        return buildJsonObject { put("desk", JsonObject(desk.mapValues { JsonObject(it.value) })) }.toString()
    }

    /** The desk scry answers [settingsDesk] over [base], as the ship takes each poke. */
    fun answerSettingsDesk(base: String = """{"desk":{}}""") {
        answerScry = { p -> if (p == "settings/desk/talon") settingsDesk(base) else null }
    }
}
