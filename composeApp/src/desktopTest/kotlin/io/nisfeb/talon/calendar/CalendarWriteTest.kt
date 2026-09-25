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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a write reads back, and when. Every request into the calendar is
 * about a second of the ship's one thread, queued behind the rest, so a
 * write reads only the lists it can have changed, and the task list is
 * asked for tasks rather than for every event there is. And the ship
 * answers a write before it applies it: read once, a late ship's old
 * copy came back and the edit looked undone until the next poll.
 */
class CalendarWriteTest {
    private val json = headersOf("Content-Type", "application/json")
    private val undated = """{"id":"t1","cal":"default","cat":"todo","meta":{"name":"Buy milk"}}"""
    private val dated = """{"id":"t2","cal":"default","cat":"todo","meta":{"name":"Pay rent"},"due_ms":1790640000000}"""

    private class Ship {
        /** Reads, path with query, since the last [clear]. */
        val reads = CopyOnWriteArrayList<String>()
        val writtenAt = AtomicLong(0L)
        fun clear() = reads.clear()
    }

    private fun calendar(
        calendars: String = """[{"id":"default","name":"Personal","kind":"local"}]""",
        tasks: (Ship) -> String = { """[$undated,$dated]""" },
        window: (Ship) -> String = { """{"rows":[]}""" },
        block: suspend (CalendarRepo, Ship) -> Unit,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val ship = Ship()
        val repo = CalendarRepo(
            HttpClient(
                MockEngine { req ->
                    val path = req.url.encodedPath
                    if (path.startsWith("/grubbery/api/poke/")) {
                        ship.writtenAt.set(System.currentTimeMillis())
                        return@MockEngine respond("", HttpStatusCode.OK, json)
                    }
                    ship.reads += path + (req.url.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: "")
                    val body = when {
                        path.endsWith("/events.json") -> tasks(ship)
                        path.endsWith("/window.json") -> window(ship)
                        path.endsWith("/calendars.json") -> calendars
                        path.endsWith("/config.json") -> """{"title":"Calendar","zone":"UTC","ball":"abc"}"""
                        path.endsWith("/google.json") -> """{"connected":true,"linked":{}}"""
                        else -> "[]"
                    }
                    respond(ByteReadChannel(body), HttpStatusCode.OK, json)
                },
            ),
            scope,
            pollIntervalMs = 60 * 60_000L,
        )
        try {
            repo.attach("https://ship.test")
            repo.refresh()
            repo.refreshTasks()
            ship.clear()
            block(repo, ship)
        } finally {
            scope.cancel()
        }
    }

    private fun Ship.asked(path: String) = reads.any { it.substringBefore('?').endsWith(path) }

    @Test
    fun `the task list asks for tasks, not every event there is`() = calendar { repo, ship ->
        repo.refreshTasks()
        assertEquals(listOf("/apps/calendar/events.json?cat=todo"), ship.reads.toList())
        assertEquals(listOf("t1", "t2"), repo.tasks.value?.map { it.id })
    }

    @Test
    fun `each write reads back only the lists it can have changed`() = calendar { repo, ship ->
        val day = LocalDate(2026, 9, 25)
        // An undated task: the task list, and no window, which it is in none of.
        assertTrue(repo.pokeEvent(eventBody(EventDraft(name = "Buy oat milk", cat = EventCat.TODO, date = day, cal = "default"), "t1")))
        assertTrue(ship.asked("/events.json") && !ship.asked("/window.json"), ship.reads.toString())

        // A dated task's tick: the list, and the windows it is shown in.
        ship.clear()
        assertTrue(repo.setDone("t2", true))
        assertTrue(ship.asked("/events.json") && ship.asked("/window.json"), ship.reads.toString())

        // An event: the windows, and not the task list.
        ship.clear()
        assertTrue(repo.pokeEvent(eventBody(EventDraft(name = "Dentist", date = day, cal = "default"), "e1")))
        assertTrue(ship.asked("/window.json") && !ship.asked("/events.json"), ship.reads.toString())

        // No write reads the calendars, their sharing or their sync.
        assertTrue(ship.reads.none { it.contains("calendars.json") || it.contains("shares.json") || it.contains("google") }, ship.reads.toString())
    }

    @Test
    fun `an edit the ship applies late is read again until it shows`() = calendar(tasks = { ship ->
        val applied = ship.writtenAt.get() > 0 && System.currentTimeMillis() - ship.writtenAt.get() > 1_500
        """[{"id":"t1","cal":"default","cat":"todo","meta":{"name":"${if (applied) "Buy oat milk" else "Buy milk"}"}}]"""
    }) { repo, _ ->
        assertEquals("Buy milk", repo.tasks.value?.single()?.name)
        val edited = EventDraft(name = "Buy oat milk", cat = EventCat.TODO, date = LocalDate(2026, 9, 25), cal = "default")
        assertTrue(repo.pokeEvent(eventBody(edited, "t1")))
        assertEquals("Buy oat milk", repo.tasks.value?.single()?.name, "read again until the edit showed")
    }

    @Test
    fun `a change that lands between two reads of one pass is read into both`() {
        // Lands as the first read after the write is answered: that read
        // has the old task, the window read just after it has the new day.
        val applied = java.util.concurrent.atomic.AtomicBoolean(false)
        val monday = 1790640000000L + 3 * 86_400_000L
        calendar(
            tasks = { ship ->
                val due = if (applied.get()) monday else 1790640000000L
                if (ship.writtenAt.get() > 0) applied.set(true)
                """[{"id":"t2","cal":"default","cat":"todo","meta":{"name":"Pay rent"},"due_ms":$due}]"""
            },
            window = {
                val l = if (applied.get()) monday else 1790640000000L
                """{"rows":[{"id":"t2","cal":"default","cat":"todo","kind":"todo","all":true,"meta":{"name":"Pay rent"},"l":$l,"r":$l}]}"""
            },
        ) { repo, _ ->
            val edited = EventDraft(name = "Pay rent", cat = EventCat.TODO, date = LocalDate(2026, 10, 1), due = LocalDate(2026, 10, 1), cal = "default")
            assertTrue(repo.pokeEvent(eventBody(edited, "t2")))
            assertEquals(monday, repo.tasks.value?.single()?.dueMs, "the task list has the new day too, not the copy read before it landed")
        }
    }

    @Test
    fun `sync status is asked for only where a calendar syncs`() {
        calendar { repo, ship ->
            repo.refresh()
            assertTrue(ship.reads.none { it.contains("google") || it.contains("caldav") || it.contains("conflicts") }, ship.reads.toString())
        }
        calendar(calendars = """[{"id":"default","name":"Personal","kind":"local"},{"id":"g","name":"Work","kind":"google"}]""") { repo, ship ->
            repo.refresh()
            assertTrue(ship.asked("/google.json") && ship.asked("/conflicts.json"), ship.reads.toString())
        }
    }
}
