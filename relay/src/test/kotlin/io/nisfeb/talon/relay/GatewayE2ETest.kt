package io.nisfeb.talon.relay

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
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

/**
 * The gateway end to end against a live %trunk at wire 11: a ship pushes
 * an iPhone's test alert through these routes, and what would go to APNs
 * is caught. Opt-in:
 *
 *   GATEWAY_E2E_SHIP=http://127.0.0.1:8192 GATEWAY_E2E_JAR=<curl cookie jar> \
 *     ./gradlew :relay:test --tests '*GatewayE2E*'
 */
class GatewayE2ETest {
    @Test
    fun `a ship's test alert reaches APNs through the gateway`() {
        val shipUrl = System.getenv("GATEWAY_E2E_SHIP") ?: run { println("GATEWAY_E2E_SHIP not set, skipping"); return }
        val cookie = File(System.getenv("GATEWAY_E2E_JAR")).readLines()
            .map { it.split('\t') }.single { it.size >= 7 && it[5].startsWith("urbauth") }.let { "${it[5]}=${it[6]}" }
        val ship = cookie.substringAfter("urbauth-~").substringBefore('=')
        val path = Files.createTempFile("relay-gw-e2e-", ".db").toFile().also { it.delete() }.absolutePath
        val db = Db(path).also { it.migrate() }
        val sent = LinkedBlockingQueue<Pair<String, String>>()
        val gateway = Gateway(
            db,
            alert = { t, p -> sent += t to alertPayload(p.title, p.body, p.patp, p.whom, p.postId, p.parent, p.nonce); ApnsResult(200, "") },
            voip = { t, p -> sent += t to p; ApnsResult(200, "") },
        )
        val port = 8194
        val server = embeddedServer(Netty, port = port) {
            installRoutes(db, ConnectionPool(db, Push(null), "e2e-secret"), "e2e-secret", OkHttpClient(), gateway)
        }.start(wait = false)
        val http = HttpClient.newHttpClient()
        fun post(url: String, body: String) = http.send(
            HttpRequest.newBuilder(URI(url)).header("Content-Type", "application/json")
                .header("Cookie", cookie).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        fun poke(json: String): Int {
            val channel = "$shipUrl/~/channel/gw-e2e-${UUID.randomUUID()}"
            val req = HttpRequest.newBuilder(URI(channel)).header("Content-Type", "application/json").header("Cookie", cookie)
                .PUT(HttpRequest.BodyPublishers.ofString("""[{"id":1,"action":"poke","ship":"$ship","app":"trunk","mark":"trunk-action","json":$json}]"""))
                .build()
            return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
                .also { http.send(HttpRequest.newBuilder(URI(channel)).header("Cookie", cookie).DELETE().build(), HttpResponse.BodyHandlers.discarding()) }
        }
        val id = "gw-e2e-${UUID.randomUUID().toString().take(8)}"
        try {
            val minted = post("http://127.0.0.1:$port/gateway/devices", """{"token":"aa11|bb22"}""")
            assertEquals(200, minted.statusCode(), minted.body())
            val dev = RelayJson.decodeFromString<GatewayDevice>(minted.body())
            assertEquals(
                204,
                poke(
                    """{"push-register":{"id":"$id","platform":"ios-gateway","gateway":"http://127.0.0.1:$port",""" +
                        """"handle":"${dev.handle}","secret":"${dev.secret}","caps":[]}}""",
                ),
            )
            val nonce = UUID.randomUUID().toString()
            assertEquals(204, poke("""{"push-test":{"id":"$id","nonce":"$nonce"}}"""))
            val (token, payload) = assertNotNull(sent.poll(30, TimeUnit.SECONDS), "no test alert reached the gateway")
            println("GATEWAY_E2E alert to $token: $payload")
            assertEquals("bb22", token)
            val p = RelayJson.parseToJsonElement(payload).let { it as kotlinx.serialization.json.JsonObject }
            assertEquals(nonce, p["nonce"].toString().trim('"'))
            assertEquals("push-test", p["event"].toString().trim('"'))
            assertEquals("~$ship", p["patp"].toString().trim('"'))
        } finally {
            poke("""{"push-unregister":"$id"}""")
            server.stop(500, 1_000)
            File(path).delete()
        }
    }
}
