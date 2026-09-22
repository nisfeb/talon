package io.nisfeb.talon.calendar

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Opening the editor.
 *
 * The editor cannot open without the event's rule breakdown, and every
 * request into a grubbery app is about a second of the ship's single
 * thread and they queue: tapping Edit sat on the screen it was already
 * on for as long as that queue was. The read happens once, and it
 * happens while the owner is reading the event rather than after they
 * have asked for the editor.
 */
class EventDetailCacheTest {
    private fun repo(asked: MutableList<String>): CalendarRepo {
        // The poller attach() starts asks at the same time this test
        // does, and a plain list loses an entry when two threads add to
        // it at once.
        val http = HttpClient(
            MockEngine { req ->
                asked += req.url.toString()
                respond(
                    """{"id":"e1","cat":"timed","kind":"once","l":0,"r":3600000,"meta":{"name":"Dinner"}}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        return CalendarRepo(http, CoroutineScope(SupervisorJob()), pollIntervalMs = 60 * 60 * 1000L)
            .also { it.attach("https://ship.test") }
    }

    @Test
    fun `an event is read once, and the reading can happen before it is asked for`() = runBlocking {
        val asked = java.util.concurrent.CopyOnWriteArrayList<String>()
        val repo = repo(asked)
        fun reads() = asked.count { "/event.json" in it }

        assertNotNull(repo.eventDetail("e1"), "the detail came back")
        assertEquals(1, reads(), "asked: " + asked.joinToString("\n"))
        assertNotNull(repo.eventDetail("e1"), "the second open has it in hand")
        assertEquals(1, reads(), "and asks the ship nothing")

        // The viewer reads ahead; the tap that follows costs nothing.
        asked.clear()
        repo.prefetchEvent("e2")
        while (reads() == 0) delay(10)
        assertNotNull(repo.eventDetail("e2"))
        assertEquals(1, reads(), "read once, by the prefetch")
        // Viewing it again reads it again: a copy from an earlier look
        // opened the editor on the event as it was then, and saving wrote
        // that back over a change made elsewhere since.
        repo.prefetchEvent("e2")
        while (reads() < 2) delay(10)
        assertEquals(2, reads(), "each view is a fresh read")
    }

    // Changed on another device, or by orrery's executor, in between:
    // the editor opens on what the ship says now, not on the first read.
    @Test
    fun `a refresh drops what was read of an event`() = runBlocking {
        var name = "Dinner"
        val asked = java.util.concurrent.CopyOnWriteArrayList<String>()
        val http = HttpClient(
            MockEngine { req ->
                asked += req.url.toString()
                val body = if ("/event.json" in req.url.toString()) {
                    """{"id":"e1","cat":"timed","kind":"once","l":0,"r":3600000,"meta":{"name":"$name"}}"""
                } else {
                    """{"rows":[]}"""
                }
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )
        val repo = CalendarRepo(http, CoroutineScope(SupervisorJob()), pollIntervalMs = 60 * 60 * 1000L).also { it.attach("https://ship.test") }
        assertEquals("Dinner", repo.eventDetail("e1")!!.let(::nameOf))
        name = "Supper"
        repo.refresh()
        assertEquals("Supper", repo.eventDetail("e1")!!.let(::nameOf), "read again after the ship said what is current")
        assertEquals(2, asked.count { "/event.json" in it })
    }

    private fun nameOf(o: kotlinx.serialization.json.JsonObject): String =
        (o["meta"] as kotlinx.serialization.json.JsonObject)["name"].toString().trim('"')
}
