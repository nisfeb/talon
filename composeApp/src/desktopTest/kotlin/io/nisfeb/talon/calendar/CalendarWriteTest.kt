package io.nisfeb.talon.calendar

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
        /** How long the ship takes over a write, a busy one being slow. */
        @Volatile var holdWriteMs = 0L
        /** How long the ship takes over a read once a write is in: a busy one, seconds. */
        @Volatile var holdReadsMs = 0L
        /** The ship refuses writes. */
        @Volatile var refuse = false
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
                        // A request cancelled while held never gets here: the write is lost.
                        if (ship.holdWriteMs > 0) kotlinx.coroutines.delay(ship.holdWriteMs)
                        if (ship.refuse) return@MockEngine respond("", HttpStatusCode.BadRequest, json)
                        ship.writtenAt.set(System.currentTimeMillis())
                        return@MockEngine respond("", HttpStatusCode.OK, json)
                    }
                    ship.reads += path + (req.url.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: "")
                    if (ship.holdReadsMs > 0 && ship.writtenAt.get() > 0) kotlinx.coroutines.delay(ship.holdReadsMs)
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
    fun `leaving the screen while a write is out does not lose it`() = calendar(tasks = { ship ->
        val due = if (ship.writtenAt.get() > 0) "1790899200000" else "1790640000000"
        """[{"id":"t2","cal":"default","cat":"todo","meta":{"name":"Pay rent"},"due_ms":$due}]"""
    }) { repo, ship ->
        ship.holdWriteMs = 800
        val edited = EventDraft(name = "Pay rent", cat = EventCat.TODO, date = LocalDate(2026, 10, 1), due = LocalDate(2026, 10, 1), cal = "default")
        // The screen's own scope, gone when the screen is: the home page's
        // today was where the move was being looked for.
        val screen = kotlinx.coroutines.CoroutineScope(SupervisorJob())
        screen.launch { repo.pokeEvent(eventBody(edited, "t2")) }
        kotlinx.coroutines.delay(200)
        screen.cancel()
        kotlinx.coroutines.withTimeout(10_000) { while (repo.tasks.value?.single()?.dueMs != 1790899200000L) kotlinx.coroutines.delay(50) }
        assertTrue(ship.writtenAt.get() > 0, "the write reached the ship")
    }

    @Test
    fun `a save is said once the ship takes it, and the task moves at once`() = calendar(tasks = { ship ->
        val due = if (ship.writtenAt.get() > 0) "1790899200000" else "1790640000000"
        """[{"id":"t2","cal":"default","cat":"todo","meta":{"name":"Pay rent"},"due_ms":$due}]"""
    }) { repo, ship ->
        ship.holdReadsMs = 1_500
        val edited = EventDraft(name = "Pay rent", cat = EventCat.TODO, date = LocalDate(2026, 10, 1), due = LocalDate(2026, 10, 1), cal = "default")
        val started = System.currentTimeMillis()
        val w = repo.writeEvent(eventBody(edited, "t2"))
        assertTrue(w.ok)
        assertTrue(System.currentTimeMillis() - started < 1_400, "answered without waiting for the reading back")
        assertEquals(1790812800000L, repo.tasks.value?.single()?.dueMs, "the task list has the new day at once")
        assertTrue(w.shown.isActive, "the ship's own copy is still being read")
        w.shown.join()
        assertEquals(1790899200000L, repo.tasks.value?.single()?.dueMs, "then the ship's own copy")
    }

    private val dentist = { l: Long -> """{"id":"e1","cal":"default","cat":"timed","kind":"once","all":false,"meta":{"name":"Dentist"},"l":$l,"r":${l + 3_600_000}}""" }

    @Test
    fun `a deleted event leaves every list at once, and a late ship's copy does not bring it back`() {
        val soon = System.currentTimeMillis() + 3_600_000
        calendar(window = { ship ->
            // Applied a second and a half after the ship answers the write.
            val applied = ship.writtenAt.get() > 0 && System.currentTimeMillis() - ship.writtenAt.get() > 1_500
            """{"rows":[${if (applied) "" else dentist(soon)}]}"""
        }) { repo, ship ->
            repo.loadRange(soon - 86_400_000, soon + 86_400_000)
            assertTrue(repo.rows.value.orEmpty().any { it.id == "e1" } && repo.rangeRows.value.orEmpty().any { it.id == "e1" })
            ship.holdWriteMs = 800
            val seen = CopyOnWriteArrayList<Boolean>()
            val watch = CoroutineScope(SupervisorJob()).apply {
                launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { repo.rows.collect { r -> seen += r.orEmpty().any { it.id == "e1" } } }
            }
            val w = watch.async { repo.writeEvent(deleteBody("e1")) }
            // The home page's today reads the window: gone before the ship answers.
            kotlinx.coroutines.withTimeout(500) { while (repo.rows.value.orEmpty().any { it.id == "e1" }) kotlinx.coroutines.delay(10) }
            assertTrue(repo.rangeRows.value.orEmpty().none { it.id == "e1" }, "and from the month")
            w.await().shown.join()
            watch.cancel()
            assertEquals(listOf(true, false), seen.distinct(), "never back once gone: $seen")
            assertTrue(repo.rangeRows.value.orEmpty().none { it.id == "e1" })
        }
    }

    @Test
    fun `a delete the ship refuses comes back`() {
        val soon = System.currentTimeMillis() + 3_600_000
        calendar(window = { """{"rows":[${dentist(soon)}]}""" }) { repo, ship ->
            repo.loadRange(soon - 86_400_000, soon + 86_400_000)
            ship.refuse = true
            assertTrue(!repo.writeEvent(deleteBody("e1")).ok)
            assertTrue(repo.rows.value.orEmpty().any { it.id == "e1" } && repo.rangeRows.value.orEmpty().any { it.id == "e1" })
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
