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
        repo.prefetchEvent("e2")
        delay(50)
        assertEquals(1, reads(), "and a second prefetch asks nothing")
    }
}
