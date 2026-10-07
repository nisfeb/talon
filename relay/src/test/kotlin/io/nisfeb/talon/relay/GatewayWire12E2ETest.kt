package io.nisfeb.talon.relay

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Trunk wire 12 through the gateway, against a live ship (sneagan,
 * 2026-10-07: "I want trunk to do as much as possible"): a DM's alert,
 * a read's clear and badge, and a notice from an app on the ship. What
 * would go to APNs is caught. Opt-in:
 *
 *   W12_SHIP=http://127.0.0.1:8192 W12_CODE_FILE=<+code> W12_PATP=~tuc \
 *   W12_PEER=http://127.0.0.1:8191 W12_PEER_CODE_FILE=<+code> W12_PEER_PATP=~pyx \
 *   W12_DM='<a chat-dm-action-2 body from the peer>' \
 *     ./gradlew :relay:test --rerun --tests '*GatewayWire12E2E*'
 */
class GatewayWire12E2ETest {
    private val http = HttpClient.newHttpClient()

    private fun login(url: String, codeFile: String): String {
        val code = File(codeFile).readText().trim()
        val r = http.send(
            HttpRequest.newBuilder(URI("$url/~/login")).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("password=$code")).build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        return r.headers().firstValue("set-cookie").get().substringBefore(';')
    }

    private fun poke(url: String, cookie: String, ship: String, app: String, mark: String, json: String): Int {
        val channel = "$url/~/channel/w12-e2e-${UUID.randomUUID()}"
        val req = HttpRequest.newBuilder(URI(channel)).header("Content-Type", "application/json").header("Cookie", cookie)
            .PUT(HttpRequest.BodyPublishers.ofString("""[{"id":1,"action":"poke","ship":"${ship.removePrefix("~")}","app":"$app","mark":"$mark","json":$json}]"""))
            .build()
        return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
            .also { http.send(HttpRequest.newBuilder(URI(channel)).header("Cookie", cookie).DELETE().build(), HttpResponse.BodyHandlers.discarding()) }
    }

    @Test
    fun `alerts, clears, badges and notices reach APNs through the gateway`() {
        val url = System.getenv("W12_SHIP") ?: run { println("W12_SHIP not set, skipping"); return }
        val patp = System.getenv("W12_PATP")
        val peerUrl = System.getenv("W12_PEER")
        val peer = System.getenv("W12_PEER_PATP")
        val cookie = login(url, System.getenv("W12_CODE_FILE"))
        val peerCookie = login(peerUrl, System.getenv("W12_PEER_CODE_FILE"))

        val path = Files.createTempFile("relay-w12-e2e-", ".db").toFile().also { it.delete() }.absolutePath
        val db = Db(path).also { it.migrate() }
        val sent = LinkedBlockingQueue<String>()
        val gateway = Gateway(
            db,
            alert = { _, p, badge -> sent += "alert ${p.event ?: "new-message"} whom=${p.whom} badge=$badge"; ApnsResult(200, "") },
            voip = { _, _ -> ApnsResult(200, "") },
            badgeOnly = { _, n -> sent += "badge $n"; ApnsResult(200, "") },
            background = { _, payload -> sent += "background $payload"; ApnsResult(200, "") },
        )
        val port = 8195
        val server = embeddedServer(Netty, port = port) {
            installRoutes(db, ConnectionPool(db, Push(null), "e2e"), "e2e", OkHttpClient(), gateway)
        }.start(wait = false)
        val id = "w12-e2e-${UUID.randomUUID().toString().take(8)}"
        fun next(match: String, secs: Long): String? {
            val until = System.currentTimeMillis() + secs * 1000
            while (System.currentTimeMillis() < until) {
                val got = sent.poll(1, TimeUnit.SECONDS) ?: continue
                println("W12_E2E: $got")
                if (match in got) return got
            }
            return null
        }
        try {
            val dev = gateway.enroll(GatewayEnroll("aa11|bb22")).second!!
            assertEquals(204, poke(url, cookie, patp, "trunk", "trunk-action",
                """{"push-register":{"id":"$id","platform":"ios-gateway","gateway":"http://127.0.0.1:$port","handle":"${dev.handle}","secret":"${dev.secret}","caps":["notice","read","badge"]}}"""))

            assertEquals(204, poke(peerUrl, peerCookie, peer, "chat", "chat-dm-action-2", System.getenv("W12_DM")))
            assertNotNull(next("alert new-message whom=$peer", 20), "the DM's alert")

            assertEquals(204, poke(url, cookie, patp, "activity", "activity-action-2",
                """{"read":{"source":{"dm":{"ship":"$peer"}},"action":{"all":{"time":null,"deep":true}}}}"""))
            val clear = assertNotNull(next("background", 20), "the read's clear")
            val p = RelayJson.parseToJsonElement(clear.removePrefix("background ")) as JsonObject
            assertEquals(listOf("read", patp, peer), listOf("event", "patp", "whom").map { p[it]!!.jsonPrimitive.content })
            assertNotNull(next("badge ", 45), "the badge after the read, within trunk's 30 s")

            assertEquals(204, poke(url, cookie, patp, "trunk", "trunk-action",
                """{"push-notice":{"tag":"cal-w12e2e","title":"Leave now","body":"E2E notice","open":null}}"""))
            assertTrue(next("alert notice whom=cal-w12e2e", 20) != null, "the notice's alert")
        } finally {
            poke(url, cookie, patp, "trunk", "trunk-action", """{"push-unregister":"$id"}""")
            server.stop(500, 1_000)
            File(path).delete()
        }
    }
}
