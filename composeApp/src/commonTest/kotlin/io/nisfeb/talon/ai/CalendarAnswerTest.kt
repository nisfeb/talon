package io.nisfeb.talon.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.calendar.CalendarRepo
import io.nisfeb.talon.orrery.Decider
import io.nisfeb.talon.orrery.Decision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [CalendarQuestion.fromCalendar] on the real clock, where it is about
 * noon: the read covers six hours back, so today asks the ship for its
 * morning and tomorrow is read from what the calendar holds.
 */
class CalendarAnswerTest {
    private val now = io.nisfeb.talon.util.nowMs()
    private val zoneId = run {
        val h = ((now / 3_600_000) % 24).toInt()
        val off = (12 - h).let { if (it > 14) it - 24 else if (it < -12) it + 24 else it }
        "UTC" + (if (off >= 0) "+" else "-") + kotlin.math.abs(off).toString().padStart(2, '0') + ":00"
    }
    private val zone = TimeZone.of(zoneId)
    private val today = kotlin.time.Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).date
    private fun row(id: String, name: String, l: Long, r: Long) =
        """{"id":"$id","cal":"default","cat":"timed","kind":"once","all":false,"l":$l,"r":$r,"meta":{"name":"$name"}}"""
    private fun task(id: String, name: String, dueDaysFromToday: Int?, done: Boolean = false) =
        """{"id":"$id","cal":"default","cat":"todo","done":$done,""" +
            // A task has a day, kept as midnight UTC (dueDate).
            (dueDaysFromToday?.let { "\"due_ms\":${today.plus(it, DateTimeUnit.DAY).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()}," } ?: "") +
            """"meta":{"name":"$name"}}"""

    /** Window reads the ship was asked for. */
    private val windows = mutableListOf<String>()

    private class Said(val p: Double, val days: String) : Decider {
        override suspend fun ask(state: JsonObject, questions: JsonObject): Decision =
            Decision(buildJsonObject {
                putJsonObject("calendar_only") { put("noul", p) }
                putJsonObject("days") { put("choice", days) }
            })
    }

    private fun answer(said: Said, windowFails: Boolean = false, block: (String?, CardSeen) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val rows = listOf(
            row("e1", "Standup", now + 3_600_000, now + 5_400_000),
            row("e2", "Dentist", now + 86_400_000, now + 90_000_000),
        ).joinToString(",", "{\"rows\":[", "]}")
        val tasks = listOf(task("t1", "Milk", 0), task("t2", "Rent", -2), task("t3", "Seeds", 1), task("t4", "Old", -3, done = true), task("t5", "Someday", null))
            .joinToString(",", "[", "]")
        val http = HttpClient(MockEngine { req ->
            val path = req.url.encodedPath
            fun json(s: String) = respond(s, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            when {
                path.endsWith("/window.json") -> {
                    windows += req.url.parameters["from"].orEmpty()
                    if (windowFails && windows.size > 1) respondError(HttpStatusCode.InternalServerError) else json(rows)
                }
                path.endsWith("/events.json") -> json(tasks)
                path.endsWith("/config.json") -> json("""{"title":"Calendar","zone":"$zoneId","ball":"b"}""")
                path.endsWith("/calendars.json") -> json("""[{"id":"default","name":"Personal","kind":"local"}]""")
                path.endsWith(".json") -> json("[]")
                else -> respondError(HttpStatusCode.NotFound)
            }
        })
        try {
            val cal = CalendarRepo(http, scope, pollIntervalMs = 60 * 60 * 1000L).also { it.attach("https://ship.example", poll = false) }
            cal.refresh()
            val seen = CardSeen()
            block(CalendarQuestion.fromCalendar(cal, said, "what's on", emptyList(), twentyFour = true, seen), seen)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `tomorrow is answered from what the calendar holds with its day and tasks`() = answer(Said(0.95, "tomorrow")) { line, seen ->
        assertEquals("Tomorrow you have one event. First is Dentist at ${DictationCards.clock(now + 86_400_000, zone, true)}. One task is due.", line)
        assertEquals(1, windows.size, "no read beyond the calendar's own")
        val card = seen.card(zone, { it }, true, now)
        assertEquals(listOf("Tomorrow" to listOf("Dentist")), card.days.map { d -> d.label to d.events.map { it.name } })
        assertEquals(listOf("Seeds"), card.tasks.map { it.name }, "only what is due then, with no overdue on a day ahead")
    }

    @Test
    fun `today asks the ship for its morning and counts what is overdue`() = answer(Said(0.95, "today")) { line, seen ->
        assertTrue(line!!.startsWith("Today you have one event."), line)
        assertTrue(line.endsWith("2 tasks are due."), line)
        assertEquals(2, windows.size, "the morning is before what the calendar holds")
        assertEquals(today.toString(), kotlin.time.Instant.fromEpochMilliseconds(windows[1].toLong()).toLocalDateTime(zone).date.toString())
        assertEquals(listOf("Rent", "Milk"), seen.card(zone, { it }, true, now).tasks.map { it.name })
    }

    @Test
    fun `not sure enough is the model's and asks the calendar nothing`() = answer(Said(0.5, "tomorrow")) { line, seen ->
        assertNull(line)
        assertEquals(1, windows.size)
        assertTrue(seen.card(zone, { it }, true, now).isEmpty)
    }

    @Test
    fun `a calendar that does not answer is the model's`() = answer(Said(0.95, "today"), windowFails = true) { line, seen ->
        assertNull(line)
        assertTrue(seen.card(zone, { it }, true, now).isEmpty, "no card of a read that failed")
    }
}
