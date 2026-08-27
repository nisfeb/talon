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
 * A ring on the watched ship must reach the device's push endpoint.
 *
 * This is the half of "does the phone actually ring" that lives on the
 * server: ship → relay SSE → hint POST. It runs against real ships and
 * a real local push endpoint, because the thing most likely to be
 * wrong is an assumption about eyre's channel envelope — specifically
 * that a diff's `id` is the *subscription* id, which is how the relay
 * tells a %trunk ring from an %activity event on the shared channel.
 *
 * Rings land on ship A only, so pointing A at a ship nobody is signed
 * into keeps the noise off a real device.
 *
 *   TRUNK_E2E=1 ./gradlew :relay:test --tests '*RingPushE2E*'
 */
class RingPushE2ETest {

    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val jsonMedia = "application/json".toMediaType()

    private fun login(url: String, code: String): String {
        val req = Request.Builder()
            .url("$url/~/login")
            .post("password=$code".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val raw = resp.headers("set-cookie").firstOrNull()
                ?: error("no set-cookie from $url")
            return raw.substringBefore(';')
        }
    }

    /** Poke `app` on the ship behind [cookie] with one action. */
    private fun poke(url: String, cookie: String, ship: String, app: String, mark: String, json: String) {
        val channel = UUID.randomUUID().toString().replace("-", "")
        val payload =
            """[{"id":1,"action":"poke","ship":"$ship","app":"$app","mark":"$mark","json":$json}]"""
        val req = Request.Builder()
            .url("$url/~/channel/$channel")
            .put(payload.toRequestBody(jsonMedia))
            .header("Cookie", cookie)
            .build()
        http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "poke failed: ${resp.code}" }
        }
    }

    @Test
    fun aRingReachesThePushEndpoint() {
        if (System.getenv("TRUNK_E2E") == null) {
            println("TRUNK_E2E not set — skipping ring push test")
            return
        }
        val calleeUrl = System.getenv("TRUNK_A_URL") ?: "http://localhost:8081"
        val calleeCode = System.getenv("TRUNK_A_CODE") ?: "ropnys-batwyd-nossyt-mapwet"
        val calleePatp = System.getenv("TRUNK_A_PATP") ?: "~nec"
        val callerUrl = System.getenv("TRUNK_B_URL") ?: "http://localhost:8082"
        val callerCode = System.getenv("TRUNK_B_CODE") ?: "navper-fopmul-figlur-darryd"

        // Stand in for the UnifiedPush distributor: capture what the
        // relay POSTs, verbatim.
        val pushes = LinkedBlockingQueue<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/push") { ex ->
            pushes.put(ex.requestBody.readBytes().decodeToString())
            ex.sendResponseHeaders(200, -1)
            ex.close()
        }
        server.start()
        val endpoint = "http://127.0.0.1:${server.address.port}/push"

        val dbFile = Files.createTempFile("relay-ring", ".db").toFile()
        dbFile.deleteOnExit()
        val db = Db(dbFile.absolutePath).also { it.migrate() }
        val deviceId = newDeviceId()
        db.upsertDevice(deviceId, endpoint, "test")

        val calleeCookie = login(calleeUrl, calleeCode)
        val callerCookie = login(callerUrl, callerCode)

        val conn = ShipConnection(
            shipRowId = 1L,
            shipUrl = calleeUrl,
            cookie = calleeCookie,
            deviceId = deviceId,
            patp = calleePatp,
            db = db,
            push = Push(),
            http = OkHttpClient(),
        )
        try {
            conn.start()
            // Let the channel open and both subscriptions land.
            Thread.sleep(6_000)

            val callId = "ring-e2e-${System.currentTimeMillis()}"
            val pokedAt = System.currentTimeMillis()
            poke(
                callerUrl, callerCookie,
                ship = System.getenv("TRUNK_B_PATP")?.removePrefix("~") ?: "feb",
                app = "trunk", mark = "trunk-action",
                json = """{"send":{"ship":"$calleePatp","sig":{"ring":{"id":"$callId"}}}}""",
            )

            val got = pushes.poll(30, TimeUnit.SECONDS)
            val elapsed = System.currentTimeMillis() - pokedAt
            assertNotNull(got, "no push arrived for the ring")
            println("push payload: $got")
            // Caller-side ring timeout is 45s; the whole wake path has
            // to fit inside it with room for the device to answer.
            println("ring -> push latency: ${elapsed}ms")
            assertTrue(elapsed < 10_000, "ring took ${elapsed}ms to become a push")
            assertTrue(""""event":"ring"""" in got, "wrong event type: $got")
            assertTrue(""""id":"$callId"""" in got, "wrong call id: $got")
            assertTrue(""""patp":"$calleePatp"""" in got, "wrong ship: $got")
            // The caller's @p is the point of the hint — without it the
            // notification can't say who is calling.
            assertTrue("\"from\":\"~" in got, "no caller in payload: $got")
            // Hint-only: no part of the media negotiation may leak.
            assertTrue("sdp" !in got && "fpr" !in got, "payload carried media data: $got")

            // A ring must not disturb the %activity cursor — that cursor
            // resumes the message stream after a crash.
            assertEquals(null, db.lastEventId(1L, deviceId), "ring advanced the message cursor")
        } finally {
            conn.stop()
            server.stop(0)
        }
    }
}
