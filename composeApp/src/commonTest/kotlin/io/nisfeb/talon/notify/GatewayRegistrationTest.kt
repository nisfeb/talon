package io.nisfeb.talon.notify

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An iPhone moves to its ship's own pushes too, with the relay doing only
 * the Apple hop (sneagan, 2026-10-06: "can iOS switch too and just use my
 * relay for the apple requirement"). The gateway requests are pinned
 * against the relay's GatewayEnroll; the register poke against trunk's
 * ios-gateway push-target (gwbtc/trunk#1).
 */
class GatewayRegistrationTest {
    private val sent = mutableListOf<String>()
    private var answer: (String) -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to """{"handle":"h1","secret":"s1"}""" }

    private val relay = RelayClient(
        HttpClient(
            MockEngine { req ->
                val body = req.body.toByteArray().decodeToString()
                sent += "${req.method.value} ${req.url} $body"
                val (status, text) = answer(body)
                respond(text, status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ),
        endpoint = { "https://relay.test/" },
    )

    private class Tokens(val token: String?) : PushTokenProvider {
        override val platform = "ios"
        override suspend fun token() = token
        override fun alertsIn(endpoint: String) = endpoint.substringAfter('|', "").isNotBlank()
    }

    private val settings = InMemoryRelaySettings("https://relay.test/")

    @Test
    fun a_first_move_mints_a_handle_and_gives_the_ship_only_that() = runTest {
        val poke = gatewayRegistration("~zod", "dev-1", settings, relay, Tokens("v1|a1"))
        assertEquals(listOf("""POST https://relay.test/gateway/devices {"token":"v1|a1"}"""), sent)
        assertEquals(
            """{"push-register":{"id":"dev-1","platform":"ios-gateway","gateway":"https://relay.test","handle":"h1","secret":"s1","caps":[]}}""",
            poke.toString(),
        )
        assertEquals(GatewayDevice("h1", "s1"), settings.gatewayFor("~zod"))
    }

    @Test
    fun new_tokens_go_behind_the_kept_handle() = runTest {
        settings.setGatewayFor("~zod", GatewayDevice("h1", "s1"))
        gatewayRegistration("~zod", "dev-1", settings, relay, Tokens("v2|a2"))
        assertEquals(listOf("""POST https://relay.test/gateway/devices {"token":"v2|a2","handle":"h1","secret":"s1"}"""), sent)
    }

    @Test
    fun a_handle_the_gateway_lost_is_minted_again() = runTest {
        settings.setGatewayFor("~zod", GatewayDevice("old", "s0"))
        answer = { if ("\"old\"" in it) HttpStatusCode.NotFound to "" else HttpStatusCode.OK to """{"handle":"h2","secret":"s2"}""" }
        val poke = gatewayRegistration("~zod", "dev-1", settings, relay, Tokens("v1|a1"))
        assertEquals(2, sent.size)
        assertTrue("\"handle\":\"h2\"" in poke.toString())
        assertEquals(GatewayDevice("h2", "s2"), settings.gatewayFor("~zod"))
    }

    @Test
    fun no_alert_token_means_no_endpoint_and_no_request() = runTest {
        assertEquals(null, gatewayRegistration("~zod", "dev-1", settings, relay, Tokens("v1|")))
        assertEquals(null, gatewayRegistration("~zod", "dev-1", settings, relay, Tokens(null)))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun a_relay_without_the_gateway_fails_the_try_and_keeps_the_relay() = runTest {
        answer = { HttpStatusCode.ServiceUnavailable to "" }
        settings.setDeviceIdFor("~zod", "relay-dev")
        val pokes = mutableListOf<String>()
        val r = moveToShipPush(
            "~zod", settings,
            ShipPushPorts(
                trunkWire = { 11 },
                poke = { pokes += it.toString() },
                register = { id -> gatewayRegistration("~zod", id, settings, relay, Tokens("v1|a1")) },
                awaitNonce = { _, _ -> true },
                relayUnregister = { error("must not leave the relay") },
                newId = { "dev-1" },
            ),
        )
        assertTrue(r is ShipPushMove.Failed, "$r")
        assertTrue(pokes.isEmpty())
        assertEquals("relay-dev", settings.deviceIdFor("~zod"))
    }

    @Test
    fun a_phone_its_ship_pushes_to_is_not_asked_to_set_up_the_relay() {
        assertTrue(shouldOfferNotificationSetup(true, "~zod", settings, justSignedIn = true))
        settings.setViaShipPush("~zod", true)
        assertEquals(false, shouldOfferNotificationSetup(true, "~zod", settings, justSignedIn = true))
    }
}
