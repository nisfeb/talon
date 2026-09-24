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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.Collections
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
internal class FakeShip(val us: String = "~zod") {
    data class Poke(val app: String, val mark: String, val json: JsonElement, val ship: String)

    val pokes: MutableList<Poke> = Collections.synchronizedList(mutableListOf())
    val scries = ConcurrentHashMap<String, String>()

    /** Every subscription opened, as `app/path`. */
    val subscribed: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Every scry path asked for, answered or not, in order. */
    val scried: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile var refuse: (Poke) -> String? = { null }

    private val stream = ByteChannel(autoFlush = true)
    private var nextEventId = 1L

    // Pokes arrive on concurrent requests; the stream takes one writer at a
    // time, or two acks interleave, one is lost, and its poke waits it out.
    private val writing = Mutex()

    /** Put a fact on the event stream, framed as eyre frames it. */
    suspend fun emit(json: String) = writing.withLock {
        stream.writeStringUtf8("id: ${nextEventId++}\ndata: $json\n\n")
    }

    /** What the app talks to the ship through; pass it as the app's client. */
    val http = HttpClient(MockEngine { req ->
        val path = req.url.encodedPath
        when {
            req.method == HttpMethod.Put -> {
                for (msg in Json.parseToJsonElement(req.body.toByteArray().decodeToString()).jsonArray) {
                    val o = msg.jsonObject
                    val id = o["id"]!!.jsonPrimitive.long
                    when (o["action"]?.jsonPrimitive?.content) {
                        "poke" -> {
                            val p = Poke(
                                o["app"]!!.jsonPrimitive.content,
                                o["mark"]!!.jsonPrimitive.content,
                                o["json"]!!,
                                o["ship"]!!.jsonPrimitive.content,
                            )
                            pokes += p
                            val err = refuse(p)
                            emit(
                                if (err == null) """{"id":$id,"response":"poke","ok":"ok"}"""
                                else """{"id":$id,"response":"poke","err":${JsonPrimitive(err)}}""",
                            )
                        }
                        "subscribe" -> {
                            subscribed += "${o["app"]?.jsonPrimitive?.content}${o["path"]?.jsonPrimitive?.content}"
                            emit("""{"id":$id,"response":"subscribe","ok":"ok"}""")
                        }
                    }
                }
                respond("", HttpStatusCode.NoContent)
            }
            req.method == HttpMethod.Get && path.startsWith("/~/channel/") ->
                respond(stream, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            path.startsWith("/~/scry/") ->
                path.removePrefix("/~/scry/").removeSuffix(".json").also { scried += it }
                    .let { scries[it] }
                    ?.let { respond(it, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
                    ?: respond("", HttpStatusCode.NotFound)
            else -> respond("", HttpStatusCode.NotFound)
        }
    })

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
}
