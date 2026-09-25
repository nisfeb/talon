package io.nisfeb.talon.calendar

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ship answers a write before it applies it, and a busy one applies
 * it seconds later. Read back once, the old copy came in and the edit
 * looked undone until the next poll, ten minutes on.
 */
class CalendarWriteTest {
    @Test
    fun `an edit the ship applies late is read again until it shows`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val json = headersOf("Content-Type", "application/json")
        val writtenAt = java.util.concurrent.atomic.AtomicLong(0L)
        val task = { name: String -> """[{"id":"t1","cal":"default","cat":"todo","meta":{"name":"$name"}}]""" }
        val repo = CalendarRepo(
            HttpClient(
                MockEngine { req ->
                    val path = req.url.encodedPath
                    when {
                        path.startsWith("/grubbery/api/poke/") -> {
                            writtenAt.set(System.currentTimeMillis())
                            respond("", HttpStatusCode.OK, json)
                        }
                        path.endsWith("/events.json") -> {
                            val applied = writtenAt.get() > 0 && System.currentTimeMillis() - writtenAt.get() > 1_500
                            respond(ByteReadChannel(task(if (applied) "Buy oat milk" else "Buy milk")), HttpStatusCode.OK, json)
                        }
                        path.endsWith("/window.json") -> respond(ByteReadChannel("""{"rows":[]}"""), HttpStatusCode.OK, json)
                        path.endsWith("/config.json") -> respond(ByteReadChannel("""{"title":"Calendar","zone":"UTC","ball":"abc"}"""), HttpStatusCode.OK, json)
                        else -> respond(ByteReadChannel("[]"), HttpStatusCode.OK, json)
                    }
                },
            ),
            scope,
            pollIntervalMs = 60 * 60_000L,
        )
        try {
            repo.attach("https://ship.test")
            repo.refresh()
            repo.refreshTasks()
            assertEquals("Buy milk", repo.tasks.value?.single()?.name)
            val edited = EventDraft(name = "Buy oat milk", cat = EventCat.TODO, date = LocalDate(2026, 9, 25), cal = "default")
            assertTrue(repo.pokeEvent(eventBody(edited, "t1")))
            assertEquals("Buy oat milk", repo.tasks.value?.single()?.name, "read again until the edit showed")
        } finally {
            scope.cancel()
        }
    }
}
