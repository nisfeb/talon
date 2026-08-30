package io.nisfeb.talon.call

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import io.nisfeb.talon.urbit.SavedSession
import io.nisfeb.talon.urbit.SessionStore
import io.nisfeb.talon.urbit.UrbitSession
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * MockEngine harness for CallController tests that push facts
 * mid-test and inspect what the controller PUT back up the channel.
 *
 * The SSE body is a ByteChannel that never closes, so the controller's
 * reconnect loop never fires: every emitted frame lands on the one
 * live channel, and a fact can't be replayed by a reconnect after the
 * test has moved on. Pokes are acked "ok" from the PUT handler so
 * controller code sequenced after a poke doesn't sit out the 15s ack
 * timeout mid-test.
 */
internal class TrunkHarness(ship: String = "~nec") {

    /** Every channel PUT body, in arrival order. Guarded by [puts]. */
    private val puts = mutableListOf<String>()

    /** Called with each PUT body as it arrives, before the poke ack.
     *  Runs on the engine's thread — keep it tiny. */
    var onPut: ((String) -> Unit)? = null

    private val sse = ByteChannel(autoFlush = true)
    private val emitLock = Mutex()
    // Well clear of the request ids echoed back in poke acks.
    private var eventId = 100L

    /** Emit one raw SSE frame carrying [json]. */
    suspend fun emit(json: String) = emitLock.withLock {
        sse.writeStringUtf8("id: ${eventId++}\ndata: $json\n\n")
    }

    /** Emit a /calls fact framed the way eyre frames one. */
    suspend fun emitFact(json: String) =
        emit("""{"id":1,"response":"diff","json":$json}""")

    private val engine = MockEngine { req ->
        when {
            req.method.value == "PUT" -> {
                val body = (req.body as TextContent).text
                synchronized(puts) { puts += body }
                onPut?.invoke(body)
                // Ack every poke in the batch so poke() returns promptly.
                val pokeIds = runCatching {
                    Json.parseToJsonElement(body).jsonArray.mapNotNull { msg ->
                        val o = msg.jsonObject
                        o["id"]?.jsonPrimitive?.contentOrNull
                            ?.takeIf { o["action"]?.jsonPrimitive?.contentOrNull == "poke" }
                    }
                }.getOrDefault(emptyList())
                for (id in pokeIds) emit("""{"id":$id,"response":"poke","ok":true}""")
                respond("", HttpStatusCode.NoContent)
            }
            req.url.encodedPath.startsWith("/~/scry") ->
                respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            else -> respond(
                sse, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
    }

    val session: UrbitSession = UrbitSession(
        HttpClient(engine),
        Store(
            SavedSession(
                shipUrl = "https://ship.test",
                ship = ship,
                cookieName = "urbauth-" + ship,
                cookieValue = "0v1",
                cookieDomain = "ship.test",
            ),
        ),
    ).also { it.tryRestore() }

    fun putsSnapshot(): List<String> = synchronized(puts) { puts.toList() }

    /** Poll until [cond] holds; throw with the PUT log otherwise. */
    suspend fun await(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            delay(10)
        }
        throw AssertionError(
            "condition not met within ${timeoutMs}ms; channel PUTs so far: " +
                putsSnapshot(),
        )
    }

    /** Poll until a PUT matching [pred] arrived, and return it. */
    suspend fun awaitPut(timeoutMs: Long = 5_000, pred: (String) -> Boolean): String {
        await(timeoutMs) { putsSnapshot().any(pred) }
        return putsSnapshot().first(pred)
    }

    /** The channel is up once the /calls watch went out. */
    suspend fun awaitConnected() {
        awaitPut { it.contains("\"action\":\"subscribe\"") }
    }

    private class Store(private val entry: SavedSession) : SessionStore {
        override fun all() = listOf(entry)
        override fun active() = entry
        override fun activeShip() = entry.ship
        override fun save(entry: SavedSession, makeActive: Boolean) {}
        override fun setActive(ship: String) {}
        override fun remove(ship: String) {}
        override fun clearAll() {}
    }

    companion object {
        /** The id inside a `send` poke's sig of [kind], or null. */
        fun sigIdIn(putBody: String, kind: String): String? = runCatching {
            Json.parseToJsonElement(putBody).jsonArray.firstNotNullOfOrNull { msg ->
                msg.jsonObject["json"]?.jsonObject
                    ?.get("send")?.jsonObject
                    ?.get("sig")?.jsonObject
                    ?.get(kind)?.jsonObject
                    ?.get("id")?.jsonPrimitive?.contentOrNull
            }
        }.getOrNull()
    }
}

/** An engine stuck gathering: its offer never completes. For tests
 *  where the interesting behavior happens before media exists. */
internal class HangingCallEngine : CallEngine {
    override val state: StateFlow<MediaState> = MutableStateFlow(MediaState.Gathering)
    override suspend fun createOffer(): SessionDesc = awaitCancellation()
    override suspend fun acceptOffer(remote: SessionDesc): SessionDesc = awaitCancellation()
    override suspend fun setAnswer(remote: SessionDesc) = Unit
    override fun setMuted(muted: Boolean) = Unit
    override val video: StateFlow<VideoState> = MutableStateFlow(VideoState())
    override suspend fun setCameraEnabled(enabled: Boolean): Boolean = false
    override fun close() = Unit
}

/** An engine whose offers/answers succeed instantly and whose media
 *  state the test scripts by hand. */
internal class ScriptedCallEngine(
    private val desc: SessionDesc = SessionDesc(
        "v=0\na=fingerprint:sha-256 AA:BB\n", "sha-256 AA:BB",
    ),
) : CallEngine {
    val stateFlow = MutableStateFlow(MediaState.Idle)
    override val state: StateFlow<MediaState> = stateFlow

    @Volatile var answersSet = 0
        private set

    override suspend fun createOffer(): SessionDesc = desc
    override suspend fun acceptOffer(remote: SessionDesc): SessionDesc = desc
    override suspend fun setAnswer(remote: SessionDesc) { answersSet++ }
    override fun setMuted(muted: Boolean) = Unit
    override val video: StateFlow<VideoState> = MutableStateFlow(VideoState())
    override suspend fun setCameraEnabled(enabled: Boolean): Boolean = false
    override fun close() = Unit
}
