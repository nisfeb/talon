package io.nisfeb.talon.notify

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A device moves from the public relay to the ship's own %trunk (gwbtc/
 * trunk#1, wire 11) only once a test push from the ship has arrived:
 * sneagan, 2026-10-06, "check the configuration is such that notifications
 * work, and then automatically migrate". Bodies pinned against trunk's
 * contract as trunk-5f sent it.
 */
class ShipPushMoveTest {
    private val pokes = mutableListOf<String>()
    private val unregistered = mutableListOf<String>()
    private var ids = 0

    private fun ports(
        wire: Int = 11,
        endpoint: String? = "https://ntfy.test/up/abc",
        delivered: Boolean = true,
        relayLetsGo: Boolean = true,
        pokeFails: Boolean = false,
    ) = ShipPushPorts(
        trunkWire = { wire },
        poke = { body: JsonElement -> if (pokeFails) error("not connected"); pokes += body.toString() },
        register = { id -> endpoint?.let { TrunkPush.register(id, it, listOf("read")) } },
        awaitNonce = { _, _ -> delivered },
        relayUnregister = { id -> unregistered += id; relayLetsGo },
        newId = { "id${++ids}" },
    )

    private fun onRelay() = InMemoryRelaySettings().apply { setDeviceIdFor("~zod", "relay-dev") }

    @Test
    fun a_ship_whose_test_push_arrives_takes_over_from_the_relay() = runTest {
        val s = onRelay()
        assertEquals(ShipPushMove.ViaShip, moveToShipPush("~zod", s, ports()))
        assertEquals(
            listOf(
                """{"push-register":{"id":"id1","platform":"unifiedpush","endpoint":"https://ntfy.test/up/abc","caps":["read"]}}""",
                """{"push-test":{"id":"id1","nonce":"id2"}}""",
            ),
            pokes,
        )
        assertEquals(listOf("relay-dev"), unregistered, "off the public relay, or every push came twice")
        assertEquals("", s.deviceIdFor("~zod"))
        assertTrue(s.viaShipPush("~zod"))
        assertEquals("id1", s.trunkDeviceIdFor("~zod"))
    }

    @Test
    fun a_test_push_that_never_comes_leaves_the_relay_in_place() = runTest {
        val s = onRelay()
        assertEquals(ShipPushMove.NotVerified, moveToShipPush("~zod", s, ports(delivered = false)))
        assertEquals("""{"push-unregister":"id1"}""", pokes.last(), "the ship forgets the device it could not reach")
        assertTrue(unregistered.isEmpty())
        assertEquals("relay-dev", s.deviceIdFor("~zod"))
        assertFalse(s.viaShipPush("~zod"))
    }

    @Test
    fun an_older_trunk_or_none_is_left_alone() = runTest {
        val s = onRelay()
        assertEquals(ShipPushMove.NotSupported, moveToShipPush("~zod", s, ports(wire = 10)))
        assertEquals(ShipPushMove.NotSupported, moveToShipPush("~zod", s, ports(wire = 0)))
        assertTrue(pokes.isEmpty() && unregistered.isEmpty())
    }

    @Test
    fun no_push_endpoint_on_the_phone_asks_nothing_of_the_ship() = runTest {
        assertEquals(ShipPushMove.NoEndpoint, moveToShipPush("~zod", onRelay(), ports(endpoint = null)))
        assertTrue(pokes.isEmpty())
    }

    @Test
    fun a_device_never_on_the_relay_moves_without_a_delete() = runTest {
        val s = InMemoryRelaySettings()
        assertEquals(ShipPushMove.ViaShip, moveToShipPush("~zod", s, ports()))
        assertTrue(unregistered.isEmpty())
    }

    @Test
    fun once_moved_each_start_registers_again_without_another_test() = runTest {
        val s = InMemoryRelaySettings().apply { setTrunkDeviceIdFor("~zod", "kept"); setViaShipPush("~zod", true) }
        assertEquals(ShipPushMove.ViaShip, moveToShipPush("~zod", s, ports()))
        assertEquals(1, pokes.size)
        assertTrue(pokes.single().startsWith("""{"push-register":{"id":"kept","""), "the same id, not a new device")
    }

    @Test
    fun a_relay_that_would_not_let_go_is_asked_again_next_start() = runTest {
        val s = onRelay()
        assertEquals(ShipPushMove.ViaShip, moveToShipPush("~zod", s, ports(relayLetsGo = false)))
        assertEquals("relay-dev", s.deviceIdFor("~zod"), "kept, to ask again")
        moveToShipPush("~zod", s, ports())
        assertEquals(listOf("relay-dev", "relay-dev"), unregistered)
        assertEquals("", s.deviceIdFor("~zod"))
    }

    @Test
    fun no_connection_is_not_an_answer() = runTest {
        val s = onRelay()
        assertTrue(moveToShipPush("~zod", s, ports(pokeFails = true)) is ShipPushMove.Failed)
        assertFalse(s.viaShipPush("~zod"))
        assertEquals("relay-dev", s.deviceIdFor("~zod"))
    }

    @Test
    fun a_test_push_that_landed_before_the_wait_still_counts() = runTest {
        PushTestNonces.received("early-nonce")
        assertTrue(PushTestNonces.await("early-nonce", 1_000))
        assertFalse(PushTestNonces.await("never-sent", 50))
    }

    // sneagan: "just make it manual only".
    @Test
    fun going_back_to_the_relay_is_the_owners_choice_and_holds() = runTest {
        val s = InMemoryRelaySettings().apply { setTrunkDeviceIdFor("~zod", "dev-t"); setViaShipPush("~zod", true) }
        leaveShipPush("~zod", s) { pokes += it.toString() }
        assertEquals(listOf("""{"push-unregister":"dev-t"}"""), pokes)
        assertFalse(s.viaShipPush("~zod"))
        pokes.clear()
        assertEquals(ShipPushMove.NotWanted, moveToShipPush("~zod", s, ports()))
        assertTrue(pokes.isEmpty(), "not moved back on the next start")
        s.setShipPushDeclined("~zod", false)
        assertEquals(ShipPushMove.ViaShip, moveToShipPush("~zod", s, ports()))
    }

    // Review of 1.8.1: trunk installed minutes after sign-in was not
    // noticed until the next app start.
    @Test
    fun trunk_arriving_after_sign_in_moves_the_device_then() = runTest {
        var wire = 0
        val s = onRelay()
        val arrived = kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
        val results = mutableListOf<ShipPushMove>()
        val job = launch {
            keepMovingToShipPush(
                "~zod", s,
                ShipPushPorts(
                    trunkWire = { wire },
                    poke = { pokes += it.toString() },
                    register = { id -> TrunkPush.register(id, "https://ntfy.test/up/abc", listOf("read")) },
                    awaitNonce = { _, _ -> true },
                    relayUnregister = { id -> unregistered += id; true },
                    newId = { "id${++ids}" },
                ),
                bootstrapping = kotlinx.coroutines.flow.flowOf(false),
                trunkArrived = arrived,
            ) { results += it }
        }
        testScheduler.advanceUntilIdle()
        assertEquals(listOf<ShipPushMove>(ShipPushMove.NotSupported), results, "no trunk yet: still waiting")
        wire = 12
        arrived.emit(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(ShipPushMove.ViaShip, results.last())
        assertTrue(job.isCompleted, "moved: nothing left to wait for")
    }

    @Test
    fun a_move_stopped_midway_leaves_the_ship_pushing_nothing_here() = runTest {
        val s = onRelay()
        val job = launch {
            moveToShipPush(
                "~zod", s,
                ShipPushPorts(
                    trunkWire = { 12 },
                    poke = { pokes += it.toString() },
                    register = { id -> TrunkPush.register(id, "https://ntfy.test/up/abc", listOf("read")) },
                    awaitNonce = { _, _ -> kotlinx.coroutines.awaitCancellation() },
                    relayUnregister = { id -> unregistered += id; true },
                    newId = { "id${++ids}" },
                ),
            )
        }
        testScheduler.advanceUntilIdle()
        job.cancel()
        job.join()
        assertEquals("""{"push-unregister":"id1"}""", pokes.last(), "or every alert came twice")
        assertFalse(s.viaShipPush("~zod"))
        assertEquals("relay-dev", s.deviceIdFor("~zod"), "the relay stays")
    }

    @Test
    fun a_moved_device_whose_trunk_went_says_so() = runTest {
        val s = onRelay().apply { setViaShipPush("~zod", true) }
        assertEquals(ShipPushMove.NotSupported, moveToShipPush("~zod", s, ports(wire = 0)))
        assertTrue("~zod" in ShipPushHealth.broken.value)
        assertTrue(keepAliveNeeded(s, "~zod", "https://ntfy.test/up/abc", shipPushBroken = true), "Talon keeps itself running to hear the ship")
        assertEquals(ShipPushMove.ViaShip, moveToShipPush("~zod", s, ports(wire = 12)))
        assertFalse("~zod" in ShipPushHealth.broken.value, "back: cleared")
    }

    @Test
    fun signing_out_takes_the_device_off_the_ships_trunk() = runTest {
        val s = InMemoryRelaySettings().apply { setViaShipPush("~zod", true); setTrunkDeviceIdFor("~zod", "t-1"); setShipPushDeclined("~zod", false) }
        forgetShipPush("~zod", s) { pokes += it.toString() }
        assertEquals(listOf("""{"push-unregister":"t-1"}"""), pokes)
        assertFalse(s.viaShipPush("~zod"))
        assertEquals("", s.trunkDeviceIdFor("~zod"))
        pokes.clear()
        forgetShipPush("~zod", s) { pokes += it.toString() }
        assertTrue(pokes.isEmpty(), "nothing registered, nothing to say")
    }
}

