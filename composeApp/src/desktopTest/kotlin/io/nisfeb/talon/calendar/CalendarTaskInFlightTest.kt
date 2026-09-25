package io.nisfeb.talon.calendar

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A new task is on the list the moment it is made, in flight, and the
 * write is the repo's, not a screen's: it goes on whatever the screen
 * does, and the stand-in leaves when the calendar's own copy arrives.
 */
class CalendarTaskInFlightTest {
    private fun MockRequestHandleScope.json(body: String) =
        respond(ByteReadChannel(body), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))

    private fun withRepo(accept: Boolean, block: suspend (CalendarRepo, CompletableDeferred<Unit>) -> Unit) {
        val scope = CoroutineScope(SupervisorJob())
        val release = CompletableDeferred<Unit>()
        val written = java.util.concurrent.atomic.AtomicBoolean(false)
        val http = HttpClient(
            MockEngine { req ->
                val path = req.url.encodedPath
                when {
                    path.startsWith("/grubbery/api/poke/") -> {
                        // Held until the test lets it through: the write in flight.
                        release.await()
                        if (accept) { written.set(true); json("") } else respondError(HttpStatusCode.InternalServerError, "refused")
                    }
                    path.endsWith("/events.json") -> json(
                        if (written.get()) """[{"id":"t1","cal":"default","cat":"todo","meta":{"name":"Buy milk"}}]""" else "[]",
                    )
                    path.endsWith("/window.json") -> json("""{"rows":[]}""")
                    path.endsWith("/calendars.json") -> json("""[{"id":"default","name":"Personal","kind":"local"}]""")
                    path.endsWith("/config.json") -> json("""{"title":"Calendar","zone":"UTC","ball":"abc123"}""")
                    path.endsWith("/share/shares.json") -> json("""{"shares":{},"offers":{},"accepted":{}}""")
                    path.endsWith("/google.json") -> json("""{"connected":false,"linked":{}}""")
                    else -> json("[]")
                }
            },
        )
        try {
            val repo = CalendarRepo(http, scope, pollIntervalMs = 60 * 60 * 1000L)
            repo.attach("https://ship.example")
            runBlocking {
                repo.refresh()
                block(repo, release)
            }
        } finally {
            scope.cancel()
        }
    }

    private val milk = EventDraft(name = "Buy milk", cat = EventCat.TODO, date = LocalDate(2026, 9, 19), due = LocalDate(2026, 9, 19))

    private suspend fun until(what: () -> Boolean) = withTimeout(5_000) { while (!what()) delay(20) }

    @Test
    fun `a new task is listed before the ship answers, and leaves when the calendar's copy arrives`() = withRepo(accept = true) { repo, release ->
        repo.addTask(milk)
        val ghost = repo.pendingTasks.value.single()
        assertEquals("Buy milk", ghost.name)
        assertEquals(1789776000000L, ghost.dueMs, "the calendar's due for that day, midnight UTC")
        assertTrue(repo.tasks.value.orEmpty().none { it.name == "Buy milk" }, "not on the ship yet")
        release.complete(Unit)
        until { repo.pendingTasks.value.isEmpty() }
        assertEquals(listOf("Buy milk"), repo.tasks.value.orEmpty().map { it.name }, "the calendar's own copy, and no gap between them")
    }

    @Test
    fun `a refusal takes the stand-in away and says why`() = withRepo(accept = false) { repo, release ->
        var said: String? = null
        repo.addTask(milk) { said = it }
        assertEquals(1, repo.pendingTasks.value.size)
        release.complete(Unit)
        until { repo.pendingTasks.value.isEmpty() }
        assertEquals("The ship did not take \"Buy milk\".", said)
    }
}
