package io.nisfeb.talon.ai

import io.nisfeb.talon.calendar.CalendarRow
import io.nisfeb.talon.calendar.CalendarTask
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import io.nisfeb.talon.orrery.Decider
import io.nisfeb.talon.orrery.Decision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.request.HttpRequestData
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class CalendarQuestionTest {
    // Friday, 16 October 2026.
    private val today = LocalDate(2026, 10, 16)
    private val zone = TimeZone.of("America/New_York")
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime(2026, 10, d, h, m).toInstant(zone).toEpochMilliseconds()
    private val now = at(16, 12, 30)

    private fun span(choice: String) = CalendarQuestion.spanOf(choice, today)

    @Test
    fun `each choice of days, from today`() {
        assertEquals(CalendarQuestion.Span(today, today, "today"), span("today"))
        assertEquals(CalendarQuestion.Span(LocalDate(2026, 10, 17), LocalDate(2026, 10, 17), "tomorrow"), span("tomorrow"))
        assertEquals(CalendarQuestion.Span(today, LocalDate(2026, 10, 22), "this week"), span("this_week"))
        assertEquals(CalendarQuestion.Span(LocalDate(2026, 10, 19), LocalDate(2026, 10, 25), "next week"), span("next_week"))
        assertEquals(CalendarQuestion.Span(LocalDate(2026, 10, 17), LocalDate(2026, 10, 18), "this weekend"), span("this_weekend"))
        assertEquals(CalendarQuestion.Span(LocalDate(2026, 10, 20), LocalDate(2026, 10, 20), "on Tuesday"), span("tuesday"))
        assertEquals(CalendarQuestion.Span(today, today, "today"), span("friday"), "the coming Friday is today")
        assertEquals(CalendarQuestion.Span(LocalDate(2026, 10, 22), LocalDate(2026, 10, 22), "on Thursday"), span("thursday"))
        assertEquals(true, span("next")?.next)
        assertNull(span("other"))
        assertNull(span("yesterday"), "a choice not offered")
        // Every day the decision model is offered is one this answers.
        val offered = (CalendarQuestion.QUESTIONS["days"] as JsonObject)["criteria"] as JsonObject
        assertEquals(14, offered.size, "six spans, seven weekdays and other")
        assertEquals(listOf("other"), offered.keys.filter { span(it) == null })
    }

    /** A decision model that answers [answers], or fails with [fails], and keeps what it was asked. */
    private class Said(val answers: JsonObject = JsonObject(emptyMap()), val fails: Throwable? = null) : Decider {
        val asked = mutableListOf<Pair<JsonObject, JsonObject>>()
        override suspend fun ask(state: JsonObject, questions: JsonObject): Decision {
            asked += state to questions
            fails?.let { throw it }
            return Decision(answers)
        }
    }

    private fun said(p: Double?, days: String?) = Said(buildJsonObject {
        if (p != null) putJsonObject("calendar_only") { put("noul", p) }
        if (days != null) putJsonObject("days") { put("choice", days); putJsonObject("probabilities") { put(days, 0.6) } }
    })

    private fun decide(d: Decider, text: String = "What do I have tomorrow?", earlier: List<DictationFlow.Earlier> = emptyList()) =
        runBlocking { CalendarQuestion.decide(d, text, earlier, today) }

    @Test
    fun `only a sure calendar-only answer is answered here, on the days it names`() {
        assertEquals(LocalDate(2026, 10, 17), decide(said(0.95, "tomorrow"))?.from)
        assertEquals(LocalDate(2026, 10, 17), decide(said(CalendarQuestion.SURE, "tomorrow"))?.from)
        // "What do I have left to do before Barcelona?", "Remind me about the dentist tomorrow": the model's.
        assertNull(decide(said(0.79, "tomorrow")))
        assertNull(decide(said(0.95, "other")), "days it cannot name")
        assertNull(decide(said(0.95, null)))
        assertNull(decide(said(null, "tomorrow")))
        assertNull(decide(Said(fails = IllegalStateException("decision model answered 402"))), "no answer is the model's too")
        assertFailsWith<CancellationException> { decide(Said(fails = CancellationException("left"))) }
    }

    @Test
    fun `it reads the words, the exchange before them, and today`() {
        val d = said(0.95, "tomorrow")
        decide(d, "And tomorrow?", listOf(DictationFlow.Earlier("What's on today?", "Today you have 3 events.")))
        val (state, questions) = d.asked.single()
        assertEquals(
            buildJsonObject {
                put("question", "And tomorrow?")
                putJsonArray("earlier") { add("owner: What's on today?"); add("answer: Today you have 3 events.") }
                put("today", "Friday, 2026-10-16")
            },
            state,
        )
        assertEquals(CalendarQuestion.QUESTIONS, questions)
        assertEquals("noul", ((questions["calendar_only"] as JsonObject)["type"] as JsonPrimitive).content)
        assertEquals("choice", ((questions["days"] as JsonObject)["type"] as JsonPrimitive).content)
        val no = (((questions["calendar_only"] as JsonObject)["criteria"] as JsonObject)["false"] as JsonPrimitive).content
        assertTrue("remind" in no && "left to do" in no && "a trip" in no, "an instruction with a day in it is not a calendar question")
    }

    @Test
    fun `with the decision model off or no key nothing is asked, and on it is asked for three seconds at most`() = runBlocking {
        val sent = mutableListOf<HttpRequestData>()
        val http = HttpClient(MockEngine { req ->
            sent += req
            respond(
                """{"answers":{"calendar_only":{"noul":0.97},"days":{"choice":"tomorrow","probabilities":{"tomorrow":0.9}}},"usage":{"input_tokens":400,"cost":0.0001}}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        // The vendor's decision model on the Armillary lease (Armillary only, #29).
        val row = armillaryRow(key = "sk-or", suggested = mapOf("default" to "a/1", "decision" to "typesafe/jev-1.13"))
        val on = paidSettings(row = row).let { it.copy(savedProfile = it.savedProfile!!.copy(jev = true)) }
        fun with(p: AiProfile) = on.copy(savedProfile = p)
        assertNull(CalendarQuestion.decider(http, with(on.savedProfile!!.copy(jev = false))), "switched off")
        assertNull(CalendarQuestion.decider(http, with(on.savedProfile!!.copy(jev = null))), "never switched on")
        assertNull(CalendarQuestion.decider(http, with(on.savedProfile!!.copy(providers = listOf(row.copy(apiKey = ""))))), "no key")
        assertNull(CalendarQuestion.decider(http, with(on.savedProfile!!.copy(providers = listOf(row.copy(suggested = mapOf("default" to "a/1")))))), "the vendor names no decision model")
        assertTrue(sent.isEmpty())

        assertEquals(LocalDate(2026, 10, 17), CalendarQuestion.decide(CalendarQuestion.decider(http, on)!!, "What do I have tomorrow?", emptyList(), today)?.from)
        val req = sent.single()
        assertEquals("https://openrouter.ai/api/alpha/decisions", req.url.toString())
        assertEquals("Bearer sk-or", req.headers[HttpHeaders.Authorization])
        assertEquals(CalendarQuestion.WAIT_MS, req.getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis)
        val body = Json.parseToJsonElement((req.body as TextContent).text) as JsonObject
        assertEquals(
            buildJsonObject {
                put("model", "typesafe/jev-1.13")
                put("state", CalendarQuestion.state("What do I have tomorrow?", emptyList(), today))
                put("questions", CalendarQuestion.QUESTIONS)
                putJsonObject("provider") { put("zdr", true) }
            },
            body,
        )
    }

    private fun event(name: String, l: Long, r: Long) = CalendarRow(id = name, meta = JsonObject(mapOf("name" to JsonPrimitive(name))), l = l, r = r)
    private fun task(name: String, due: LocalDate) = CalendarTask(
        id = name, meta = JsonObject(mapOf("name" to JsonPrimitive(name))), cat = "todo",
        dueMs = LocalDateTime(due.year, due.month, due.day, 0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds(),
    )

    private val week = listOf(
        event("Routing R&D", at(16, 10), at(16, 11)),
        event("Core Sync", at(16, 12), at(16, 13)),
        event("Ballet", at(16, 16, 45), at(16, 19, 15)),
        event("Linus's game", at(17, 14, 30), at(17, 16, 30)),
        event("Mass", at(18, 9), at(18, 10)),
    )

    private fun say(span: CalendarQuestion.Span, tasks: List<CalendarTask> = emptyList()) =
        CalendarQuestion.answer(span, week, tasks, zone, now, twentyFour = false)

    @Test
    fun `today says how many, what is on now and what is next`() {
        assertEquals(
            "Today you have 3 events. You're in Core Sync until 1:00 PM. Next is Ballet at 4:45 PM. One task is due.",
            say(span("today")!!, listOf(task("Passport app", today))),
        )
    }

    @Test
    fun `a week says how many over how many days, and what is next`() {
        assertEquals("This week: 5 events over 3 days. You're in Core Sync until 1:00 PM. Next is Ballet today at 4:45 PM.", say(span("this_week")!!))
    }

    @Test
    fun `a day ahead says what comes first, and nothing is nothing`() {
        assertEquals("Tomorrow you have one event. First is Linus's game at 2:30 PM.", say(span("tomorrow")!!))
        assertEquals("Nothing on your calendar on Tuesday.", say(span("tuesday")!!))
        assertEquals("Next up, Ballet today at 4:45 PM.", say(span("next")!!))
    }

    @Test
    fun `an overdue task counts on a span from today, and an all-day first event is all day`() {
        val overdue = task("Staples return", LocalDate(2026, 10, 14))
        assertEquals(
            "Today you have 3 events. You're in Core Sync until 1:00 PM. Next is Ballet at 4:45 PM. One task is due.",
            say(span("today")!!, listOf(overdue)),
        )
        val trip = CalendarRow(
            id = "trip", meta = JsonObject(mapOf("name" to JsonPrimitive("Spain trip"))), cat = "allday", all = true,
            l = LocalDateTime(2026, 10, 20, 0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds(),
            r = LocalDateTime(2026, 10, 21, 0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds(),
        )
        assertEquals(
            "On Tuesday you have one event. First is Spain trip all day.",
            CalendarQuestion.answer(span("tuesday")!!, listOf(trip), emptyList(), zone, now, false),
        )
    }
}
