package io.nisfeb.talon.notify

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An iPhone hears from its ship only through the relay. Every iPhone but
 * one had never registered (the way in was a technical Settings panel),
 * and that one had registered without its alert token, so every message
 * to it was dropped (2026-10-06). Bodies are pinned against the relay's
 * own RegisterRequest and EndpointRequest (relay/.../Routes.kt).
 */
class RelayEnrollmentTest {
    private val asked = mutableListOf<Pair<String, String>>()
    private var registerOk = true
    private var endpointRoute = HttpStatusCode.NoContent

    private val client = RelayClient(
        HttpClient(MockEngine { req ->
            val path = req.url.encodedPath
            asked += path to req.body.toByteArray().decodeToString()
            val json = headersOf("Content-Type", "application/json")
            when {
                path == "/register" && registerOk -> respond("""{"deviceId":"dev-1","ok":true}""", HttpStatusCode.OK, json)
                path == "/register" -> respond("""{"deviceId":"","ok":false,"error":"ship login failed"}""", HttpStatusCode.Unauthorized, json)
                path.endsWith("/endpoint") -> respond("", endpointRoute)
                else -> respond("", HttpStatusCode.NoContent)
            }
        }),
        endpoint = { "https://relay.test" },
    )

    private class Tokens(var token: String?) : PushTokenProvider {
        override val platform = "ios"
        override val caps = listOf("read")
        override suspend fun token(): String? = token
        override suspend fun missingTokenReason() = "no token from Apple"
        override fun alertsIn(endpoint: String) = endpoint.substringAfter('|', "").isNotBlank()
    }

    @Test
    fun yes_registers_this_device_with_its_code_and_keeps_what_the_relay_said() = runTest {
        val settings = InMemoryRelaySettings().apply { setDeclinedFor("~zod", true) }
        val r = enrollDevice(client, settings, Tokens("voip1|alert1"), "~zod", "https://zod.test", "lidlut-tabwed")
        assertEquals(Enrollment.On("dev-1", alerts = true), r)
        val body = Json.parseToJsonElement(asked.first { it.first == "/register" }.second).jsonObject
        assertEquals(
            mapOf("platform" to "ios", "pushEndpoint" to "voip1|alert1", "deviceId" to "", "shipUrl" to "https://zod.test", "patp" to "~zod", "code" to "lidlut-tabwed"),
            body.mapValues { it.value.jsonPrimitive.content },
        )
        assertEquals("dev-1", settings.deviceIdFor("~zod"))
        assertEquals("voip1|alert1", settings.registeredEndpointFor("~zod"))
        assertFalse(settings.declinedFor("~zod"), "saying yes later undoes a not now")
        assertEquals("""{"caps":["read"]}""", asked.first { it.first == "/devices/dev-1/caps" }.second)
    }

    @Test
    fun registered_without_an_alert_token_it_says_messages_will_not_alert() = runTest {
        val r = enrollDevice(client, InMemoryRelaySettings(), Tokens("voip1|"), "~zod", "https://zod.test", "code")
        assertEquals(Enrollment.On("dev-1", alerts = false), r)
    }

    @Test
    fun no_token_asks_nothing_of_the_relay() = runTest {
        val settings = InMemoryRelaySettings()
        assertEquals(Enrollment.NoToken("no token from Apple"), enrollDevice(client, settings, Tokens(null), "~zod", "https://zod.test", "code"))
        assertTrue(asked.isEmpty())
        assertEquals("", settings.deviceIdFor("~zod"))
    }

    @Test
    fun a_relay_that_cannot_sign_in_keeps_nothing() = runTest {
        registerOk = false
        val settings = InMemoryRelaySettings()
        val r = enrollDevice(client, settings, Tokens("voip1|alert1"), "~zod", "https://zod.test", "wrong")
        assertTrue(r is Enrollment.Refused, r.toString())
        assertEquals("", settings.deviceIdFor("~zod"))
        assertEquals("", settings.registeredEndpointFor("~zod"))
    }

    @Test
    fun an_alert_token_that_came_later_goes_to_the_relay_without_the_code() = runTest {
        val settings = InMemoryRelaySettings().apply { setDeviceIdFor("~zod", "dev-1"); setRegisteredEndpointFor("~zod", "voip1|") }
        assertTrue(refreshRegisteredEndpoint(client, settings, Tokens("voip1|alert1"), "~zod"))
        assertEquals("/devices/dev-1/endpoint" to """{"pushEndpoint":"voip1|alert1"}""", asked.single())
        assertEquals("voip1|alert1", settings.registeredEndpointFor("~zod"))
        asked.clear()
        assertTrue(refreshRegisteredEndpoint(client, settings, Tokens("voip1|alert1"), "~zod"))
        assertTrue(asked.isEmpty(), "the same endpoint is not sent again")
    }

    @Test
    fun an_older_relay_without_the_route_leaves_the_endpoint_to_send_again() = runTest {
        endpointRoute = HttpStatusCode.NotFound
        val settings = InMemoryRelaySettings().apply { setDeviceIdFor("~zod", "dev-1"); setRegisteredEndpointFor("~zod", "voip1|") }
        assertFalse(refreshRegisteredEndpoint(client, settings, Tokens("voip1|alert1"), "~zod"))
        assertEquals("voip1|", settings.registeredEndpointFor("~zod"))
    }

    @Test
    fun a_device_not_on_the_relay_sends_nothing() = runTest {
        assertFalse(refreshRegisteredEndpoint(client, InMemoryRelaySettings(), Tokens("voip1|alert1"), "~zod"))
        assertTrue(asked.isEmpty())
    }

    @Test
    fun who_is_asked_and_when() {
        val s = InMemoryRelaySettings()
        assertTrue(shouldOfferNotificationSetup(true, "~zod", s, justSignedIn = false), "at launch, never asked")
        assertFalse(shouldOfferNotificationSetup(false, "~zod", s, justSignedIn = true), "a device that does not need the relay")
        assertFalse(shouldOfferNotificationSetup(true, null, s, justSignedIn = true))
        s.setDeclinedFor("~zod", true)
        assertFalse(shouldOfferNotificationSetup(true, "~zod", s, justSignedIn = false), "not now holds at launch")
        assertTrue(shouldOfferNotificationSetup(true, "~zod", s, justSignedIn = true), "signing in again asks again")
        s.setDeviceIdFor("~zod", "dev-1")
        assertFalse(shouldOfferNotificationSetup(true, "~zod", s, justSignedIn = true), "already on the relay")
    }

    // Signed in to ~zod, then on ~bus before answering: ~bus was offered
    // the setup with ~zod's code, which the relay refuses.
    @Test
    fun a_held_code_is_its_own_ships_only() {
        val held = "~zod" to "lidlut-tabwed"
        assertEquals("lidlut-tabwed", heldCodeFor(held, "~zod"))
        assertNull(heldCodeFor(held, "~bus"), "not another ship's")
        assertNull(heldCodeFor(held, null))
        assertNull(heldCodeFor(null, "~zod"))
        val s = InMemoryRelaySettings()
        s.setDeclinedFor("~bus", true)
        assertFalse(
            shouldOfferNotificationSetup(true, "~bus", s, justSignedIn = heldCodeFor(held, "~bus") != null),
            "another ship's sign-in does not count as this one's",
        )
    }
}
