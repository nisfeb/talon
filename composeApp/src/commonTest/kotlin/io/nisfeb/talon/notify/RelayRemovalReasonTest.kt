package io.nisfeb.talon.notify

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Talon tells the relay why it removes a device, so the relay's log can
 * say a phone moved to its ship's own push rather than leave it looking
 * like a lost registration (a user's report, 2026-10-08).
 */
class RelayRemovalReasonTest {
    @Test
    fun `a removal carries its reason, and none where none is given`() = runTest {
        val asked = mutableListOf<String>()
        val http = HttpClient(MockEngine { req ->
            assertEquals(HttpMethod.Delete, req.method)
            asked += req.url.encodedPathAndQuery
            respond("", HttpStatusCode.NoContent)
        })
        val client = RelayClient(http) { "https://relay.example/" }
        assertTrue(client.unregister("dev-1", RelayClient.REASON_SHIP_PUSH))
        assertTrue(client.unregister("dev-2", RelayClient.REASON_OFF))
        assertTrue(client.unregister("dev-3", RelayClient.REASON_FORGOTTEN))
        assertTrue(client.unregister("dev-4"))
        assertEquals(listOf("/devices/dev-1?reason=ship-push", "/devices/dev-2?reason=off", "/devices/dev-3?reason=forgotten", "/devices/dev-4"), asked)
    }
}
