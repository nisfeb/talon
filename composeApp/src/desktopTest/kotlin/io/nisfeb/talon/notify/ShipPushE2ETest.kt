package io.nisfeb.talon.notify

import io.nisfeb.talon.call.TrunkWire
import io.nisfeb.talon.urbit.SavedSession
import io.nisfeb.talon.urbit.SessionStore
import io.nisfeb.talon.urbit.UrbitSession
import io.nisfeb.talon.util.createAppHttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The move to the ship's own pushes against a live %trunk at wire 11
 * (gwbtc/trunk#1), with a listener standing in for the phone's
 * UnifiedPush endpoint. Opt-in, like the other end-to-end tests:
 *
 *   SHIP_PUSH_E2E_URL=http://127.0.0.1:8192 SHIP_PUSH_E2E_CODE_FILE=<+code file> \
 *   SHIP_PUSH_E2E_LISTENER_LOG=<the listener's posts.jsonl> \
 *   SHIP_PUSH_E2E_ENDPOINT_BASE=http://127.0.0.1:8193/up \
 *     ./gradlew :composeApp:desktopTest --tests '*ShipPushE2E*'
 *
 * It leaves the device registered on the ship (its id is printed), so a
 * real message can be checked arriving at the listener afterwards.
 */
class ShipPushE2ETest {
    private class MemStore : SessionStore {
        private var s: SavedSession? = null
        override fun all() = listOfNotNull(s)
        override fun active() = s
        override fun activeShip() = s?.ship
        override fun save(entry: SavedSession, makeActive: Boolean) { s = entry }
        override fun setActive(ship: String) {}
        override fun remove(ship: String) { s = null }
        override fun clearAll() { s = null }
    }

    @Test
    fun aShipsTrunkTakesOverOnceItsTestPushArrives() {
        val url = System.getenv("SHIP_PUSH_E2E_URL") ?: run { println("SHIP_PUSH_E2E_URL not set, skipping"); return }
        val code = File(System.getenv("SHIP_PUSH_E2E_CODE_FILE")).readText().trim()
        val log = File(System.getenv("SHIP_PUSH_E2E_LISTENER_LOG"))
        val base = System.getenv("SHIP_PUSH_E2E_ENDPOINT_BASE") ?: "http://127.0.0.1:8193/up"
        runBlocking {
            val session = UrbitSession(createAppHttpClient(), MemStore())
            session.login(url, code).getOrThrow()
            val ship = session.ourPatp
            val ch = session.openChannel()
            // A channel exists once something is put on it: subscribe first,
            // as the app does, then read its stream (where poke acks arrive).
            ch.subscribe(TrunkWire.AGENT, "/calls")
            val events = ch.events().launchIn(this)
            val path = "talon-e2e-${UUID.randomUUID().toString().take(8)}"
            val endpoint = "$base/$path"
            val settings = InMemoryRelaySettings().apply { setDeviceIdFor(ship, "relay-dev") }
            val unregistered = mutableListOf<String>()
            val seen = mutableListOf<String>()
            val ports = ShipPushPorts(
                trunkWire = { TrunkWire.parseWireVersion(ch.scry(TrunkWire.AGENT, "/version")) },
                poke = { ch.poke(TrunkWire.AGENT, TrunkPush.MARK, it) },
                register = { id -> TrunkPush.register(id, endpoint, listOf("read")) },
                awaitNonce = { nonce, timeoutMs ->
                    withTimeoutOrNull(timeoutMs) {
                        while (true) {
                            log.readLines().firstOrNull { "/$path" in it && nonce in it }?.let { seen += it; return@withTimeoutOrNull true }
                            delay(500)
                        }
                        @Suppress("UNREACHABLE_CODE") false
                    } == true
                },
                relayUnregister = { id -> unregistered += id; true },
                newId = { UUID.randomUUID().toString() },
            )
            val r = moveToShipPush(ship, settings, ports, timeoutMs = 30_000)
            println("SHIP_PUSH_E2E result=$r device=${settings.trunkDeviceIdFor(ship)} endpoint=$endpoint")
            assertEquals(ShipPushMove.ViaShip, r)
            assertEquals(listOf("relay-dev"), unregistered, "off the public relay once the ship's push arrived")
            val got = Json.parseToJsonElement(seen.single()).jsonObject
            val body = Json.parseToJsonElement(got["body"]!!.jsonPrimitive.content).jsonObject
            assertEquals(setOf("event", "patp", "nonce"), body.keys)
            assertEquals("push-test", body["event"]!!.jsonPrimitive.content)
            assertEquals(ship, body["patp"]!!.jsonPrimitive.content)
            assertEquals("60", got["ttl"]!!.jsonPrimitive.content)
            assertEquals("high", got["urgency"]!!.jsonPrimitive.content)
            events.cancel()
            runCatching { ch.delete() }
        }
    }
}
