package io.nisfeb.talon.notify

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.nisfeb.talon.data.ThreadUnreadEntity
import io.nisfeb.talon.data.UnreadEntity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The unread count on the app icon (sneagan, 2026-10-06: "add the ability
 * for iOS users to enable badges on the app icon"). Request bodies pinned
 * against the relay's BadgeRequest and GatewayBadge.
 */
class AppIconBadgeTest {
    private val sent = mutableListOf<String>()
    private var status = HttpStatusCode.NoContent
    private val relay = RelayClient(
        HttpClient(
            MockEngine { req ->
                sent += "${req.url.encodedPath} ${req.body.toByteArray().decodeToString()}"
                respond("", status)
            },
        ),
        endpoint = { "https://relay.test" },
    )
    private val settings = InMemoryRelaySettings()

    @Test
    fun the_count_is_what_notified_in_chats_and_threads() {
        val chats = listOf(UnreadEntity("~bus", 9, 2, 0L), UnreadEntity("chat/~zod/x", 40, 1, 0L))
        val threads = listOf(ThreadUnreadEntity("chat/~zod/x", "~bus/1", 3, 3, 0L))
        assertEquals(6, badgeCount(chats, threads))
        assertEquals(0, badgeCount(emptyList(), emptyList()))
    }

    @Test
    fun a_relay_iphone_reports_by_device_id() = runTest {
        settings.setDeviceIdFor("~zod", "dev-1")
        assertTrue(reportBadge("~zod", 4, settings, relay))
        reportBadge("~zod", null, settings, relay)
        assertEquals(listOf("""/devices/dev-1/badge {"count":4}""", """/devices/dev-1/badge {"count":null}"""), sent)
    }

    @Test
    fun a_phone_on_its_ship_reports_through_the_gateway() = runTest {
        settings.setDeviceIdFor("~zod", "stale")
        settings.setViaShipPush("~zod", true)
        settings.setGatewayFor("~zod", GatewayDevice("h1", "s1"))
        reportBadge("~zod", 2, settings, relay)
        assertEquals(listOf("""/gateway/badge {"handle":"h1","secret":"s1","count":2}"""), sent)
    }

    @Test
    fun no_push_registration_means_nothing_to_tell() = runTest {
        assertTrue(reportBadge("~zod", 2, settings, relay))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun a_relay_that_refuses_is_reported_as_failed() = runTest {
        settings.setDeviceIdFor("~zod", "dev-1")
        status = HttpStatusCode.NotFound
        assertEquals(false, reportBadge("~zod", 1, settings, relay))
    }
}
