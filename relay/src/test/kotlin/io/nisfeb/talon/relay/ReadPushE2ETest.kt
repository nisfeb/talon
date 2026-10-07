package io.nisfeb.talon.relay

import com.sun.net.httpserver.HttpServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A chat read on the watched ship must reach a "read"-capable device as
 * a read push, once: the relay watched only %activity /v4, where Tlon
 * sends no ordinary read, so it never sent one (sneagan, 2026-10-06:
 * "fix the relay read pushes too"). Against a real %activity, since the
 * path a read goes out on is the thing that was wrong.
 *
 *   READ_E2E_URL=http://127.0.0.1:8192 READ_E2E_CODE_FILE=<+code> READ_E2E_PATP=~tuc \
 *   READ_E2E_PEER_URL=http://127.0.0.1:8191 READ_E2E_PEER_CODE_FILE=<+code> READ_E2E_PEER=~pyx \
 *   READ_E2E_DM='<a chat-dm-action-2 body from the peer>' \
 *     ./gradlew :relay:test --rerun --tests '*ReadPushE2E*'
 */
class ReadPushE2ETest {
    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val jsonMedia = "application/json".toMediaType()

    private fun login(url: String, codeFile: String): String {
        val code = java.io.File(codeFile).readText().trim()
        val req = Request.Builder().url("$url/~/login")
            .post("password=$code".toRequestBody("application/x-www-form-urlencoded".toMediaType())).build()
        http.newCall(req).execute().use { resp -> return resp.headers("set-cookie").first().substringBefore(';') }
    }

    private fun poke(url: String, cookie: String, ship: String, app: String, mark: String, json: String) {
        val channel = "$url/~/channel/read-e2e-${UUID.randomUUID()}"
        val payload = """[{"id":1,"action":"poke","ship":"${ship.removePrefix("~")}","app":"$app","mark":"$mark","json":$json}]"""
        http.newCall(Request.Builder().url(channel).put(payload.toRequestBody(jsonMedia)).header("Cookie", cookie).build())
            .execute().use { check(it.isSuccessful) { "poke $app failed: ${it.code}" } }
        http.newCall(Request.Builder().url(channel).delete().header("Cookie", cookie).build()).execute().close()
    }

    @Test
    fun aReadReachesTheDeviceOnce() {
        val url = System.getenv("READ_E2E_URL") ?: run { println("READ_E2E_URL not set, skipping"); return }
        val patp = System.getenv("READ_E2E_PATP")
        val peerUrl = System.getenv("READ_E2E_PEER_URL")
        val peer = System.getenv("READ_E2E_PEER")

        val pushes = LinkedBlockingQueue<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/push") { ex ->
            pushes.put(ex.requestBody.readBytes().decodeToString())
            ex.sendResponseHeaders(200, -1)
            ex.close()
        }
        server.start()
        val dbFile = Files.createTempFile("relay-read", ".db").toFile().also { it.deleteOnExit() }
        val db = Db(dbFile.absolutePath).also { it.migrate() }
        val deviceId = newDeviceId()
        db.upsertDevice(deviceId, "http://127.0.0.1:${server.address.port}/push", "test")
        db.setCaps(deviceId, listOf("read"))

        val cookie = login(url, System.getenv("READ_E2E_CODE_FILE"))
        val peerCookie = login(peerUrl, System.getenv("READ_E2E_PEER_CODE_FILE"))
        // No read timeout, as the relay runs it: an idle SSE stream is not a dead one.
        val conn = ShipConnection(1L, url, cookie, deviceId, patp, db, Push(), http)
        try {
            conn.start()
            Thread.sleep(6_000)
            poke(peerUrl, peerCookie, peer, "chat", "chat-dm-action-2", System.getenv("READ_E2E_DM"))
            Thread.sleep(4_000)
            // Deep: a read push says the whole chat is read, threads too, so
            // a chat with an unread reply under it (as ~tuc's has) sends none
            // until that goes as well.
            poke(url, cookie, patp, "activity", "activity-action-2",
                """{"read":{"source":{"dm":{"ship":"$peer"}},"action":{"all":{"time":null,"deep":true}}}}""")
            val reads = mutableListOf<String>()
            val until = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < until) {
                val got = pushes.poll(500, TimeUnit.MILLISECONDS) ?: continue
                println("push: $got")
                if ("\"event\":\"read\"" in got) reads += got
            }
            assertEquals(listOf("""{"event":"read","patp":"$patp","whom":"$peer"}"""), reads, "one read push for the chat")
        } finally {
            conn.stop()
            server.stop(0)
        }
    }
}
