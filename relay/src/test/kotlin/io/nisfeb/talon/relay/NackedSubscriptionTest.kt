package io.nisfeb.talon.relay

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The relay subscribes to %trunk on every ship it watches, but most
 * ships have no %trunk installed. If eyre answered a nacked
 * subscription by tearing down the whole channel — or by refusing the
 * batched PUT outright — adding calls would silently kill chat
 * notifications for every user without the desk.
 *
 * So: subscribe to a real agent and a nonexistent one in one PUT, and
 * prove the real subscription still delivers.
 *
 *   TRUNK_E2E=1 ./gradlew :relay:test --tests '*NackedSubscription*'
 */
class NackedSubscriptionTest {

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
            return resp.headers("set-cookie").firstOrNull()?.substringBefore(';')
                ?: error("no set-cookie from $url")
        }
    }

    private fun put(url: String, cookie: String, channel: String, payload: String): Int {
        val req = Request.Builder()
            .url("$url/~/channel/$channel")
            .put(payload.toRequestBody(jsonMedia))
            .header("Cookie", cookie)
            .build()
        http.newCall(req).execute().use { return it.code }
    }

    @Test
    fun aNackedSubscriptionDoesNotKillItsChannel() {
        if (System.getenv("TRUNK_E2E") == null) {
            println("TRUNK_E2E not set — skipping nacked subscription test")
            return
        }
        val calleeUrl = System.getenv("TRUNK_A_URL") ?: "http://localhost:8081"
        val calleeCode = System.getenv("TRUNK_A_CODE") ?: "ropnys-batwyd-nossyt-mapwet"
        val calleePatp = System.getenv("TRUNK_A_PATP") ?: "~nec"
        val callerUrl = System.getenv("TRUNK_B_URL") ?: "http://localhost:8082"
        val callerCode = System.getenv("TRUNK_B_CODE") ?: "navper-fopmul-figlur-darryd"

        val cookie = login(calleeUrl, calleeCode)
        val callerCookie = login(callerUrl, callerCode)
        val ship = calleePatp.removePrefix("~")
        val channel = UUID.randomUUID().toString().replace("-", "")

        // Materialize, then subscribe to a real agent AND a bogus one
        // in a single batched PUT — the shape the relay uses.
        put(calleeUrl, cookie, channel, "[]")
        val code = put(
            calleeUrl, cookie, channel,
            """[{"id":1,"action":"subscribe","ship":"$ship","app":"trunk","path":"/calls"},""" +
                """{"id":2,"action":"subscribe","ship":"$ship","app":"no-such-agent","path":"/nope"}]""",
        )
        assertTrue(code in 200..299, "batched PUT with one bad subscribe was refused: HTTP $code")

        val events = LinkedBlockingQueue<String>()
        val source: EventSource = EventSources.createFactory(http).newEventSource(
            Request.Builder()
                .url("$calleeUrl/~/channel/$channel")
                .header("Cookie", cookie)
                .header("Accept", "text/event-stream")
                .build(),
            object : EventSourceListener() {
                override fun onEvent(s: EventSource, id: String?, type: String?, data: String) {
                    events.put(data)
                }
            },
        )
        try {
            Thread.sleep(5_000)

            val callId = "nack-probe-${System.currentTimeMillis()}"
            put(
                callerUrl, callerCookie, UUID.randomUUID().toString().replace("-", ""),
                """[{"id":1,"action":"poke","ship":"${System.getenv("TRUNK_B_PATP")?.removePrefix("~") ?: "feb"}","app":"trunk",""" +
                    """"mark":"trunk-action","json":{"send":{"ship":"$calleePatp",""" +
                    """"sig":{"ring":{"id":"$callId"}}}}}]""",
            )

            // The good subscription must still deliver, despite its
            // sibling having been refused on the same channel.
            var ring: String? = null
            val deadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < deadline) {
                val ev = events.poll(5, TimeUnit.SECONDS) ?: continue
                if (callId in ev) { ring = ev; break }
            }
            assertNotNull(ring, "a nacked sibling subscription killed the live one")
            println("survived; ring event: ${ring.take(160)}")
        } finally {
            source.cancel()
        }
    }
}
