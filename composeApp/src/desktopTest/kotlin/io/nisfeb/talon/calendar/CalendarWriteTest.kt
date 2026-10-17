package io.nisfeb.talon.calendar

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
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
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
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
        /** The ship refuses writes whose body holds this, and takes the rest. */
        @Volatile var refuseIf: String? = null
        /** Every poke the ship took, its body, in order. */
        val pokes = CopyOnWriteArrayList<String>()
        /** Every other write (a share, a revoke, an import): its path. */
        val posts = CopyOnWriteArrayList<String>()
        /**
         * Why the calendar refuses a poke it has taken, by its body, or null
         * to keep it: it answers 200 and keeps the refusal in outcomes.json.
         */
        @Volatile var refuseLater: (String) -> String? = { null }
        val refusals = CopyOnWriteArrayList<String>()
        /** Down: the web server in front of the ship answers everything with its 502 page. */
        @Volatile var down = false
        /** Every read is refused 403: the session is over. */
        @Volatile var signedOut = false
        /** config.json fails (a 500), as a busy or crashed nexus does. */
        @Volatile var configFails = false
        /** The calendar's own zone in config.json; null for none. */
        @Volatile var zone: String? = "UTC"
        fun clear() = reads.clear()
    }

    private fun calendar(
        calendars: String = """[{"id":"default","name":"Personal","kind":"local"}]""",
        tasks: (Ship) -> String = { """[$undated,$dated]""" },
        window: (Ship) -> String = { """{"rows":[]}""" },
        pollMs: Long = 60 * 60_000L,
        /** The app's scope is a screen's, whose Job is plain: one failed child cancels it. */
        plainJob: Boolean = false,
        setup: Ship.() -> Unit = {},
        block: suspend (CalendarRepo, Ship) -> Unit,
    ) = runBlocking {
        val scope = CoroutineScope(if (plainJob) kotlinx.coroutines.Job() else SupervisorJob())
        val ship = Ship().apply(setup)
        val repo = CalendarRepo(
            HttpClient(
                MockEngine { req ->
                    val path = req.url.encodedPath
                    if (ship.down) return@MockEngine respond(io.nisfeb.talon.mail.NGINX_502, HttpStatusCode.BadGateway, headersOf("Content-Type", "text/html"))
                    if (path.startsWith("/grubbery/api/poke/")) {
                        // A request cancelled while held never gets here: the write is lost.
                        if (ship.holdWriteMs > 0) kotlinx.coroutines.delay(ship.holdWriteMs)
                        val body = req.body.toByteArray().decodeToString()
                        val refuseIf = ship.refuseIf
                        if (ship.refuse || (refuseIf != null && refuseIf in body)) {
                            return@MockEngine respond("", HttpStatusCode.BadRequest, json)
                        }
                        ship.pokes += body
                        ship.refuseLater(body)?.let { why ->
                            val poke = kotlinx.serialization.json.Json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
                            ship.refusals += buildJsonObject {
                                put("at_ms", System.currentTimeMillis())
                                put("action", (poke["action"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "")
                                put("id", (poke["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "")
                                put("why", why)
                            }.toString()
                        }
                        ship.writtenAt.set(System.currentTimeMillis())
                        return@MockEngine respond("", HttpStatusCode.OK, json)
                    }
                    if (req.method == io.ktor.http.HttpMethod.Post) {
                        if (ship.holdWriteMs > 0) kotlinx.coroutines.delay(ship.holdWriteMs)
                        if (ship.refuse) return@MockEngine respond("", HttpStatusCode.BadRequest, json)
                        ship.posts += path
                        return@MockEngine respond("""{"notified":true}""", HttpStatusCode.OK, json)
                    }
                    ship.reads += path + (req.url.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: "")
                    if (ship.signedOut) return@MockEngine respond("""{"error":"forbidden"}""", HttpStatusCode.Forbidden, json)
                    if (ship.holdReadsMs > 0 && ship.writtenAt.get() > 0) kotlinx.coroutines.delay(ship.holdReadsMs)
                    val body = when {
                        path.endsWith("/events.json") -> tasks(ship)
                        path.endsWith("/window.json") -> window(ship)
                        path.endsWith("/calendars.json") -> calendars
                        path.endsWith("/config.json") && ship.configFails -> return@MockEngine respond("", HttpStatusCode.InternalServerError, json)
                        path.endsWith("/config.json") -> """{"title":"Calendar","zone":${ship.zone?.let { "\"$it\"" } ?: "null"},"ball":"abc","lead_min":30}"""
                        path.endsWith("/zones.json") -> """["UTC","${kotlinx.datetime.TimeZone.currentSystemDefault().id}"]"""
                        path.endsWith("/outcomes.json") -> """{"refusals":[${ship.refusals.joinToString(",")}],"faults":{}}"""
                        path.endsWith("/google.json") -> """{"connected":true,"linked":{}}"""
                        else -> "[]"
                    }
                    respond(ByteReadChannel(body), HttpStatusCode.OK, json)
                },
            ),
            scope,
            pollIntervalMs = pollMs,
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

    // The heads-up before every timed event: set, it is what the page
    // shows; refused, the page keeps showing what the ship still has.
    @Test
    fun `a refused heads-up change leaves the ship's own on show`() = calendar { repo, ship ->
        assertEquals(30, repo.leadMin.value)
        assertTrue(repo.remindersKnown.value, "the config says so, with no rows to say it")
        ship.refuse = true
        assertTrue(!repo.setLeadMin(0))
        assertEquals(30, repo.leadMin.value)
        ship.refuse = false
        assertTrue(repo.setLeadMin(10))
        assertEquals(10, repo.leadMin.value)
    }

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

    // Each screen kept its own ticks, and leaving it lost them: back on the
    // home page a ticked task stood unticked until the write came through.
    @Test
    fun `a tick is in ticking at once, stays past a screen leaving, and goes with the ship's word`() = calendar(tasks = { ship ->
        if (ship.writtenAt.get() > 0) """[${undated.dropLast(1)},"done":true},$dated]""" else """[$undated,$dated]"""
    }) { repo, ship ->
        ship.holdWriteMs = 800
        val screen = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val tap = screen.launch { repo.setDone("t1", true) }
        kotlinx.coroutines.delay(100)
        assertEquals(mapOf("t1" to true), repo.ticking.value, "shown ticked before the ship has answered")
        tap.cancel()
        screen.cancel()
        kotlinx.coroutines.delay(300)
        assertEquals(mapOf("t1" to true), repo.ticking.value, "the screen that ticked it is gone; the tick is not")
        kotlinx.coroutines.withTimeout(10_000) { while (repo.ticking.value.isNotEmpty()) kotlinx.coroutines.delay(20) }
        assertEquals(true, repo.tasks.value?.first { it.id == "t1" }?.done, "the ship's word is in the list when the tick goes")
    }

    @Test
    fun `a tick the ship refuses goes from ticking, and the task is as it was`() = calendar { repo, ship ->
        ship.refuse = true
        assertEquals(false, repo.setDone("t1", true))
        assertEquals(emptyMap(), repo.ticking.value)
        assertEquals(false, repo.tasks.value?.first { it.id == "t1" }?.done)
    }

    // Undone as a whole list, so any other task changed in between (an
    // edit to another one, a tick) left the refused edit on show.
    @Test
    fun `a refused edit goes back alone, and another task changed meanwhile stays changed`() = calendar { repo, ship -> kotlinx.coroutines.coroutineScope {
        ship.holdWriteMs = 800
        ship.refuseIf = "\"t2\""
        val rent = EventDraft(name = "Pay rent", cat = EventCat.TODO, date = LocalDate(2026, 10, 1), due = LocalDate(2026, 10, 1), cal = "default")
        val milk = EventDraft(name = "Buy oat milk", cat = EventCat.TODO, date = LocalDate(2026, 10, 1), cal = "default")
        val refused = async { repo.writeEvent(eventBody(rent, "t2"), readBack = false) }
        kotlinx.coroutines.delay(200)
        val taken = async { repo.writeEvent(eventBody(milk, "t1"), readBack = false) }
        kotlinx.coroutines.delay(100)
        assertEquals(1790812800000L, repo.tasks.value?.first { it.id == "t2" }?.dueMs, "both edits on show while the ship thinks")
        assertTrue(!refused.await().ok)
        assertEquals(1790640000000L, repo.tasks.value?.first { it.id == "t2" }?.dueMs, "the refused edit is undone")
        assertEquals("Buy oat milk", repo.tasks.value?.first { it.id == "t1" }?.name, "the other edit is not")
        assertTrue(taken.await().ok)
        assertEquals("Buy oat milk", repo.tasks.value?.first { it.id == "t1" }?.name)
    } }

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

    private fun event(id: String, name: String, l: Long) =
        """{"id":"$id","cal":"default","cat":"timed","kind":"once","all":false,"meta":{"name":"$name"},"l":$l,"r":${l + 3_600_000}}"""
    private val dentist = { l: Long -> event("e1", "Dentist", l) }

    @Test
    fun `a deleted event leaves every list at once, and a late ship's copy does not bring it back`() {
        val soon = System.currentTimeMillis() + 3_600_000
        calendar(window = { ship ->
            // Applied a second and a half after the ship answers the write.
            val applied = ship.writtenAt.get() > 0 && System.currentTimeMillis() - ship.writtenAt.get() > 1_500
            """{"rows":[${listOfNotNull(dentist(soon).takeUnless { applied }, event("e2", "Swim", soon + 60_000)).joinToString(",")}]}"""
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
            // Only it: the rest stays on show, not a list waiting to be read.
            assertEquals(listOf("e2"), repo.rows.value?.map { it.id })
            assertEquals(listOf("e2"), repo.rangeRows.value?.map { it.id }, "and from the month")
            w.await().shown.join()
            watch.cancel()
            assertEquals(listOf(true, false), seen.distinct(), "never back once gone: $seen")
            assertTrue(repo.rangeRows.value.orEmpty().none { it.id == "e1" })
        }
    }

    @Test
    fun `an edit is read back into the window and the month, over the spans they cover`() {
        val soon = System.currentTimeMillis() + 3_600_000
        calendar(window = { ship ->
            val applied = ship.writtenAt.get() > 0 && System.currentTimeMillis() - ship.writtenAt.get() > 1_500
            """{"rows":[${event("e1", if (applied) "Dentist at 3" else "Dentist", soon)}]}"""
        }) { repo, ship ->
            repo.loadRange(soon - 86_400_000, soon + 86_400_000)
            ship.clear()
            assertTrue(repo.pokeEvent(eventBody(EventDraft(name = "Dentist at 3", date = LocalDate(2026, 9, 25), cal = "default"), "e1")))
            assertEquals("Dentist at 3", repo.rows.value?.single()?.name, "the home page's today")
            assertEquals("Dentist at 3", repo.rangeRows.value?.single()?.name, "the calendar's month")
            // The window read back is the one a refresh reads, and the month the one on screen.
            val spans = ship.reads.filter { "window.json" in it }.map { r ->
                val q = r.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=').toLong() }
                q.getValue("to") - q.getValue("from")
            }.toSet()
            assertEquals(setOf(CalendarRepo.BEHIND_MS + CalendarRepo.AHEAD_MS, 2 * 86_400_000L), spans)
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

    // The beat the nexus takes to apply a poke is the reading's to wait:
    // waited inside the write, every save stayed on screen that much longer.
    @Test
    fun `a save is answered as soon as the ship takes it, the beat left to the reading`() = calendar { repo, _ ->
        repo.refreshTasks()
        val started = System.nanoTime()
        val w = repo.writeEvent(buildJsonObject { put("action", "edit-event"); put("id", "t1"); put("cat", "todo"); put("cal", "default"); put("meta", buildJsonObject { put("name", "Buy oat milk") }) })
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(w.ok)
        assertTrue(tookMs < CalendarRepo.APPLY_BEAT_MS, "answered in $tookMs ms")
        assertEquals("Buy oat milk", repo.tasks.value?.first { it.id == "t1" }?.name, "in the list already")
    }

    // A full read is up to nine requests of the ship's one thread, about a
    // second each, and it ran on every focus: every alt-tab on desktop.
    @Test
    fun `coming back minutes after a read asks the ship nothing`() = calendar { repo, ship ->
        repo.setForeground(false)
        repo.setForeground(true)
        kotlinx.coroutines.delay(300)
        assertTrue(ship.reads.isEmpty(), "${ship.reads}")
    }

    @Test
    fun `a stale read asks for the events and tasks, not the rest`() = calendar { repo, ship ->
        repo.refreshIfStale(0)
        assertTrue(ship.asked("/window.json") && ship.asked("/events.json"), "${ship.reads}")
        assertTrue(ship.reads.none { r -> listOf("/calendars.json", "/config.json", "/shares.json", "/tags.json", "/google.json").any { r.substringBefore('?').endsWith(it) } }, "${ship.reads}")
    }

    // ─── an event's edit shown everywhere at Save ──────────────────

    // The home page's today reads the window, and only the calendar screen
    // showed an edit before the ship's copy came back: Home kept the old
    // event for as long as a busy ship took.
    @Test
    fun `an event's edit is in the window and the month at once, and back if refused`() {
        val soon = System.currentTimeMillis() + 3_600_000
        calendar(window = { """{"rows":[${dentist(soon)}]}""" }) { repo, ship ->
            repo.loadRange(soon - 86_400_000, soon + 86_400_000)
            ship.holdWriteMs = 800
            val day = LocalDate(2026, 9, 25)
            val moved = EventDraft(name = "Dentist at 3", date = day, minuteOfDay = 15 * 60, durMin = 30, cal = "default")
            val at3 = day.atTime(15, 0).toInstant(kotlinx.datetime.TimeZone.UTC).toEpochMilliseconds()
            val w = CoroutineScope(SupervisorJob()).async { repo.writeEvent(eventBody(moved, "e1"), readBack = false) }
            kotlinx.coroutines.delay(200)
            assertTrue(ship.pokes.isEmpty(), "the ship has not taken it yet")
            for (rows in listOf(repo.rows.value, repo.rangeRows.value)) {
                val e1 = rows?.single { it.id == "e1" }
                assertEquals("Dentist at 3", e1?.name, "the home page's today and the month, at once")
                assertEquals(at3 to at3 + 30 * 60_000L, e1?.l to e1?.r, "on its new time, in the calendar's zone")
            }
            val taken = w.await()
            assertTrue(taken.ok)
            // Taken, then refused by the calendar: the screen's check undoes it.
            taken.undo()
            assertEquals("Dentist", repo.rows.value?.single()?.name)
            assertEquals(soon, repo.rangeRows.value?.single()?.l)

            ship.holdWriteMs = 0
            ship.refuse = true
            assertTrue(!repo.writeEvent(eventBody(moved, "e1"), readBack = false).ok)
            assertEquals("Dentist", repo.rows.value?.single()?.name, "refused: as it was")
            assertEquals("Dentist", repo.rangeRows.value?.single()?.name)
        }
    }

    @Test
    fun `undoing an edit leaves alone a copy the ship has read back since`() {
        val soon = System.currentTimeMillis() + 3_600_000
        calendar(window = { ship -> """{"rows":[${event("e1", if (ship.pokes.isEmpty()) "Dentist" else "Dentist at 3", soon)}]}""" }) { repo, _ ->
            val w = repo.writeEvent(eventBody(EventDraft(name = "Dentist at 3", date = LocalDate(2026, 9, 25), cal = "default"), "e1"))
            w.shown.join()
            w.undo()
            assertEquals("Dentist at 3", repo.rows.value?.single()?.name, "the ship's own copy stays")
        }
    }

    // ─── what the calendar refused after taking it ─────────────────

    // "Added to Work" for an event the calendar then refused: the ship
    // acks a poke before reading it, and keeps why it refused in outcomes.json.
    @Test
    fun `a task the calendar refuses after taking it is said, kept for later, and leaves the list`() = calendar(
        setup = { refuseLater = { if ("Buy bread" in it) "the task could not be read" else null } },
    ) { repo, _ ->
        val said = CopyOnWriteArrayList<String>()
        repo.addTask(EventDraft(name = "Buy bread", cat = EventCat.TODO, date = LocalDate(2026, 9, 25), cal = "default")) { said += it }
        assertEquals(1, repo.pendingTasks.value.size, "on the list at once")
        kotlinx.coroutines.withTimeout(10_000) { while (repo.pendingTasks.value.isNotEmpty()) kotlinx.coroutines.delay(50) }
        assertEquals(listOf("\"Buy bread\" was not added: the task could not be read"), said.toList())
        assertEquals("\"Buy bread\" was not added: the task could not be read", repo.unheard.value, "said when the calendar next opens, if no screen heard it")
    }

    @Test
    fun `an event from a chat card the calendar refuses is not called added`() {
        val soon = System.currentTimeMillis() + 3_600_000
        calendar(
            setup = { refuseLater = { if ("Picnic" in it) "no such calendar" else null } },
            window = { ship -> """{"rows":[${if (ship.pokes.any { "Tea" in it }) event("e9", "Tea", soon) else ""}]}""" },
        ) { repo, ship ->
            assertTrue(!repo.addShared("default", "Picnic", soon, soon + 3_600_000))
            assertEquals(1, ship.pokes.size)
            assertEquals("\"Picnic\" was not added: no such calendar", repo.unheard.value)
            assertTrue(repo.addShared("default", "Tea", soon, soon + 3_600_000), "one it keeps is added")
        }
    }

    @Test
    fun `an event written behind a chat says why it did not land`() = calendar(
        setup = { refuseLater = { if ("Picnic" in it) "no such calendar" else null } },
    ) { repo, ship ->
        val said = CopyOnWriteArrayList<String>()
        val picnic = eventBody(EventDraft(name = "Picnic", date = LocalDate(2026, 9, 25), cal = "default"))
        repo.writeInBackground(picnic) { said += it }
        kotlinx.coroutines.withTimeout(10_000) { while (said.isEmpty()) kotlinx.coroutines.delay(50) }
        assertEquals("\"Picnic\" was not added: no such calendar", said.single())
        ship.down = true
        repo.writeInBackground(picnic) { said += it }
        kotlinx.coroutines.withTimeout(10_000) { while (said.size < 2) kotlinx.coroutines.delay(50) }
        assertTrue("isn't answering" in said[1] && "did not take" !in said[1], said[1])
    }

    // ─── a ship down is not a refusal, nor a refusal a ship down ────

    @Test
    fun `a share write while the ship is down says so, and a refusal after it says refused`() = calendar { repo, ship ->
        ship.down = true
        assertTrue(!repo.revoke("default", "~nec"))
        assertTrue("isn't answering" in repo.failedLine("refused"), repo.failedLine("refused"))
        assertTrue(!repo.importIcs("default", "BEGIN:VCALENDAR"))
        assertTrue("isn't answering" in repo.failedLine("refused"))
        ship.down = false
        ship.refuse = true
        assertTrue(!repo.accept("~nec/work"))
        assertEquals("refused", repo.failedLine("refused"), "not the ship down of the write before")
    }

    // ─── writes carried on past the screen that made them ───────────

    // A share, revoke, offer answered, sync, make-local, import and log
    // cleared each ran on the calendar screen's scope, and leaving the
    // screen while a busy ship held one cancelled it.
    @Test
    fun `every calendar write goes on after the screen that made it has gone`() = calendar { repo, ship ->
        ship.holdWriteMs = 600
        val writes: List<Pair<String, suspend () -> Any?>> = listOf(
            "/share/share" to { repo.share("default", "~nec", edit = false) },
            "/share/revoke" to { repo.revoke("default", "~nec") },
            "/share/accept" to { repo.accept("~nec/work") },
            "/share/decline" to { repo.decline("~nec/work") },
            "/share/sync" to { repo.syncShares() },
            "/migrate" to { repo.makeLocal("family") },
            "/import" to { repo.importIcs("default", "BEGIN:VCALENDAR") },
            "/google/conflicts/clear" to { repo.clearConflicts() },
        )
        for ((path, write) in writes) {
            ship.clear()
            val screen = CoroutineScope(SupervisorJob())
            screen.launch { write() }
            kotlinx.coroutines.delay(150)
            screen.cancel()
            kotlinx.coroutines.withTimeout(10_000) { while (ship.posts.none { it.endsWith(path) }) kotlinx.coroutines.delay(20) }
            // And read back behind it, as when the screen stays.
            kotlinx.coroutines.withTimeout(10_000) { while (!ship.asked("/window.json")) kotlinx.coroutines.delay(20) }
        }
    }

    @Test
    fun `a failure inside a carried write does not stop the repo's other work`() = calendar(plainJob = true) { repo, ship ->
        ship.down = true
        val thrown = runCatching { repo.share("default", "~nec", edit = false) }.exceptionOrNull()
        assertTrue(thrown is io.nisfeb.talon.mail.AuspexError.Unreachable, "$thrown")
        ship.down = false
        assertTrue(repo.revoke("default", "~nec"), "the repo's scope still runs writes")
    }

    // ─── the calendar's own zone ────────────────────────────────────

    // A failed config read left the zone unknown, and the device's zone
    // was written over the calendar's own: a traveller's phone moved it.
    @Test
    fun `a config read that fails never sets the calendar's zone`() = calendar(setup = { configFails = true }) { repo, ship ->
        repo.refresh()
        assertTrue(ship.pokes.none { "\"config\"" in it }, "${ship.pokes}")
        assertEquals(null, repo.notice.value)
    }

    @Test
    fun `a calendar that says it has no zone takes the device's`() = calendar(setup = { zone = null }) { _, ship ->
        val config = ship.pokes.single { "\"config\"" in it }
        assertTrue(kotlinx.datetime.TimeZone.currentSystemDefault().id in config, config)
    }

    // ─── signed out, and in again ───────────────────────────────────

    // A 403 stopped the poll for the session: signed in again, the
    // calendar was read only when somebody opened it.
    @Test
    fun `the poll stopped by a signed-out answer starts again with the next read that goes through`() = calendar(
        pollMs = 200,
        setup = { signedOut = true },
    ) { repo, ship ->
        assertEquals(CalendarAvailability.SIGNED_OUT, repo.availability.value)
        ship.signedOut = false
        ship.clear()
        kotlinx.coroutines.delay(800)
        assertTrue(ship.reads.isEmpty(), "stopped: ${ship.reads}")
        repo.refresh()
        assertEquals(CalendarAvailability.PRESENT, repo.availability.value)
        ship.clear()
        kotlinx.coroutines.withTimeout(5_000) { while (!ship.asked("/window.json")) kotlinx.coroutines.delay(20) }
    }
}
