package io.nisfeb.talon.call

import io.ktor.client.plugins.websocket.webSocketSession
import io.nisfeb.talon.util.createAppHttpClient
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Galène refuses a websocket whose Origin header names a host not in its
 * allowOrigin list (asimov's Galène has one since trunk wire 16, 2026-10-10).
 * A browser always sends one; Talon's own client must send none, or every
 * party-line join from the app would be refused. This records the upgrade
 * request the app's real HTTP client sends (OkHttp, as on Android).
 */
class GaleneOriginTest {
    @Test
    fun theAppsWebsocketUpgradeCarriesNoOrigin() {
        val server = ServerSocket(0)
        val headers = CompletableFuture<List<String>>()
        thread(isDaemon = true) {
            server.accept().use { s ->
                val reader = s.getInputStream().bufferedReader()
                val lines = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                headers.complete(lines)
                s.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            }
        }
        val http = createAppHttpClient()
        try {
            runBlocking { runCatching { http.webSocketSession("ws://127.0.0.1:${server.localPort}/ws") } }
            val sent = headers.get(10, TimeUnit.SECONDS)
            assertTrue(sent.first().startsWith("GET /ws"), sent.toString())
            assertTrue(sent.any { it.startsWith("Upgrade:", ignoreCase = true) }, "it is the websocket upgrade: $sent")
            assertTrue(sent.none { it.startsWith("Origin:", ignoreCase = true) }, "no Origin: $sent")
        } finally {
            http.close()
            server.close()
        }
    }
}
