package io.nisfeb.talon.calendar

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A new ship must never be greeted by the last one's rows, zone, or calendars. */
class CalendarDetachTest {

    private fun window(name: String) = """{"rows":[{"id":"0v1","cal":"home","meta":{"name":"$name"},"l":100,"r":200}]}"""
    private val calendars = """[{"id":"home","name":"Home","color":"#ff0000","kind":"local","count":1}]"""
    private val config = """{"title":"Calendar","zone":"Europe/London","ball":"x"}"""

    private fun repo(scope: CoroutineScope, ship: () -> String) =
        CalendarRepo(
            HttpClient(
                MockEngine { req ->
                    val name = if (ship() == "a") "Ship A event" else "Ship B event"
                    // The next ship answers a moment later, so what shows before it does is seen.
                    if (ship() == "b") delay(300)
                    when {
                        "/window" in req.url.encodedPath -> respond(ByteReadChannel(window(name)), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                        "/calendars" in req.url.encodedPath -> respond(ByteReadChannel(calendars), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                        "/config" in req.url.encodedPath -> respond(ByteReadChannel(config), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                        else -> respondError(HttpStatusCode.NotFound, """{"error":"none"}""")
                    }
                },
            ),
            scope,
            pollIntervalMs = 60 * 60 * 1000L,
        )

    @Test
    fun `detach clears the ship's state and a re-attach reads the new ship fresh`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            var ship = "a"
            val r = repo(scope) { ship }
            r.attach("https://a.example")
            waitFor { r.rows.value?.isNotEmpty() == true && r.zone.value != null }
            assertEquals(listOf("Ship A event"), r.rows.value?.map { it.name })
            assertEquals("Europe/London", r.zone.value)
            assertEquals(listOf("Home"), r.calendars.value.map { it.name })

            r.detach()
            assertNull(r.rows.value, "the last ship's rows are gone")
            assertNull(r.zone.value, "its zone is gone")
            assertNull(r.tasks.value)
            assertEquals(emptyList(), r.calendars.value)
            assertEquals(CalendarAvailability.UNKNOWN, r.availability.value)

            ship = "b"
            r.attach("https://b.example")
            waitFor { r.rows.value?.isNotEmpty() == true }
            assertEquals(listOf("Ship B event"), r.rows.value?.map { it.name }, "the new ship's own rows, not the old one's")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `attaching straight to another ship shows none of the last one's`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            var ship = "a"
            val r = repo(scope) { ship }
            r.attach("https://a.example")
            waitFor { r.rows.value?.isNotEmpty() == true }
            ship = "b"
            r.attach("https://b.example")
            assertNull(r.rows.value, "no rows until the new ship answers")
            assertEquals(emptyList(), r.calendars.value)
            waitFor { r.rows.value?.isNotEmpty() == true }
            assertEquals(listOf("Ship B event"), r.rows.value?.map { it.name })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a new task goes to the default calendar only while it can be written to`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val json = headersOf("Content-Type", "application/json")
        val cals = """[{"id":"theirs","name":"Theirs","color":"#ff0000","kind":"local","count":0},""" +
            """{"id":"work","name":"Work","color":"#00ff00","kind":"local","count":0},""" +
            """{"id":"home","name":"Home","color":"#0000ff","kind":"local","count":0}]"""
        val r = CalendarRepo(
            HttpClient(
                MockEngine { req ->
                    val path = req.url.encodedPath
                    when {
                        path.endsWith("/calendars.json") -> respond(ByteReadChannel(cals), HttpStatusCode.OK, json)
                        path.endsWith("/shares.json") -> respond(ByteReadChannel("""{"accepted":{"theirs":{"key":"~bus/cal","mode":"read"}}}"""), HttpStatusCode.OK, json)
                        "/window" in path -> respond(ByteReadChannel("""{"rows":[]}"""), HttpStatusCode.OK, json)
                        "/config" in path -> respond(ByteReadChannel(config), HttpStatusCode.OK, json)
                        // The write is held: what is asserted is the stand-in.
                        req.method == io.ktor.http.HttpMethod.Post -> { delay(5_000); respond("{}", HttpStatusCode.OK, json) }
                        else -> respond(ByteReadChannel("[]"), HttpStatusCode.OK, json)
                    }
                },
            ),
            scope,
            pollIntervalMs = 60 * 60 * 1000L,
        )
        try {
            r.attach("https://a.example")
            waitFor { r.calendars.value.size == 3 && "theirs" in r.readOnly }
            val day = kotlinx.datetime.LocalDate(2026, 9, 25)
            r.defaultCalendar.value = "home"
            assertEquals("home", r.addTask(EventDraft(name = "a", date = day)).cal, "the default, not merely the first")
            r.defaultCalendar.value = "theirs"
            assertEquals("work", r.addTask(EventDraft(name = "b", date = day)).cal, "never one shared with us read-only")
        } finally {
            scope.cancel()
        }
    }

    private suspend fun waitFor(done: suspend () -> Boolean) {
        repeat(250) {
            if (done()) return
            delay(20)
        }
        error("timed out")
    }
}
