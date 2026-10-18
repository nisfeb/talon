package io.nisfeb.talon.ai

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.nisfeb.talon.calendar.CalendarRepo
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.UnreadEntity
import io.nisfeb.talon.mail.MailRepo
import io.nisfeb.talon.orrery.Decider
import io.nisfeb.talon.orrery.Decision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [Briefing.day], which the voice card's opener, Brief me and the model's
 * day_overview all read: what is new, what is next today, and the card.
 * On the real clock, where it is about noon.
 */
class BriefingDayTest {
    private val now = System.currentTimeMillis()
    private val zoneId = run {
        val h = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).hour
        val off = (12 - h).let { if (it > 14) it - 24 else if (it < -12) it + 24 else it }
        "UTC" + (if (off >= 0) "+" else "-") + kotlin.math.abs(off).toString().padStart(2, '0') + ":00"
    }
    private val zone = TimeZone.of(zoneId)
    private val tmp = createTempDirectory(prefix = "talon-day-").toFile()
    private val db = Room.databaseBuilder<AppDatabase>(File(tmp, "t.db").absolutePath)
        .setDriver(BundledSQLiteDriver()).fallbackToDestructiveMigration(dropAllTables = true).build()
    private val scope = CoroutineScope(SupervisorJob())

    @AfterTest
    fun close() {
        scope.cancel()
        db.close()
        tmp.deleteRecursively()
    }

    private fun row(id: String, l: Long, r: Long, cat: String = "timed", all: Boolean = false) =
        """{"id":"$id","cal":"default","cat":"$cat","kind":"once","all":$all,"l":$l,"r":$r,"meta":{"name":"$id"}}"""

    private val hour = 3_600_000L
    private val rows = listOf(
        row("Breakfast", now - 3 * hour, now - 2 * hour),
        row("Standup", now - 60_000, now + hour),
        row("Holiday", now + 10 * 60_000, now + 11 * 60_000, cat = "allday", all = true),
        row("Water plants", now + 5 * 60_000, now + 6 * 60_000, cat = "todo"),
        row("Lunch", now + 2 * hour, now + 3 * hour),
        row("Tea", now + 30 * 60_000, now + hour),
        row("Dentist", now + 26 * hour, now + 27 * hour),
    )

    private fun calendar(): CalendarRepo {
        val http = HttpClient(MockEngine { req ->
            val path = req.url.encodedPath
            fun json(s: String) = respond(s, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            when {
                path.endsWith("/window.json") -> json(rows.joinToString(",", "{\"rows\":[", "]}"))
                path.endsWith("/config.json") -> json("""{"title":"Calendar","zone":"$zoneId","ball":"b"}""")
                path.endsWith(".json") -> json("[]")
                else -> respondError(HttpStatusCode.NotFound)
            }
        })
        return CalendarRepo(http, scope, pollIntervalMs = 60 * 60 * 1000L).also { it.attach("https://ship.example", poll = false) }
    }

    private fun mail(fails: Boolean = false): MailRepo {
        val inbox = """{"total":2,"offset":0,"limit":50,"view":"inbox","threads":[
            {"id":"0v1","subject":"Seed catalogue","from":"~nec","snippet":"","verdict":"verified","forged":false,"count":1,"last":$now,"unread":true,"participants":[],"unreadable":0,"archived":false,"labels":[]},
            {"id":"0v2","subject":"Old news","from":"~nec","snippet":"","verdict":"verified","forged":false,"count":1,"last":$now,"unread":false,"participants":[],"unreadable":0,"archived":false,"labels":[]}]}"""
        val http = HttpClient(MockEngine { req ->
            if (!fails && req.url.encodedPath.endsWith("/api/inbox")) respond(inbox, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            else respondError(HttpStatusCode.InternalServerError)
        })
        return MailRepo(http, scope, pollIntervalMs = 60 * 60 * 1000L).also { it.attach("https://ship.example", poll = false) }
    }

    /** One question in the unread, by the decision model's word. */
    private object OneQuestion : Decider {
        override suspend fun ask(state: JsonObject, questions: JsonObject) =
            Decision(buildJsonObject { putJsonObject("q0") { put("noul", 0.9) } })
    }

    private fun unread() = runBlocking {
        db.messages().upsert(MessageEntity("~bus", "1", "~bus", now - 60_000, """[{"inline":["can you grab milk?"]}]""", "/chat"))
        db.unreads().upsert(UnreadEntity("~bus", count = 3, notifyCount = 0, recencyMs = now - 60_000))
    }

    @Test
    fun `the counts the next event today and the day's card`() = runBlocking {
        unread()
        val cal = calendar()
        var read = false
        val seen = CardSeen()
        val day = Briefing.day(db, "~zod", mail(), cal, OneQuestion, { zone }, twentyFour = true, name = { "Nec" }, seen = seen, calendarRead = { cal.refresh(); read = true })
        assertTrue(read, "the calendar's read was waited for")
        assertEquals(1, day.mail, "the unread mail only")
        assertFalse(day.mailLate)
        assertEquals(3, day.chats)
        assertEquals(1, day.questions)
        // Not a task, not all day, not begun, not tomorrow: the soonest of the rest.
        assertEquals("Tea at ${DictationCards.clock(now + 30 * 60_000, zone, true)}, in about 30 minutes", day.firstEvent)
        assertEquals(listOf("Today"), day.card.days.map { it.label })
        val shown = day.card.days.single().events.map { it.name }
        assertTrue(shown.containsAll(listOf("Breakfast", "Standup", "Tea", "Lunch")) && "Dentist" !in shown && "Water plants" !in shown, shown.toString())
        assertEquals(listOf("Seed catalogue" to "Nec"), day.card.mail.map { it.subject to it.from })
        assertEquals(day.card, seen.card(zone, { "Nec" }, true), "the run's card is given the same")
        assertEquals("You have 1 new email and 3 chat messages, 1 of them a question for you.", Briefing.countsLine(day.mail, day.chats, day.questions, day.mailLate))
    }

    @Test
    fun `mail that does not answer is late and not none and no decider says no count`() = runBlocking {
        val cal = calendar().also { it.refresh() }
        val day = Briefing.day(db, "~zod", mail(fails = true), cal, null, { zone }, twentyFour = false, name = { it }, seen = CardSeen())
        assertNull(day.mail)
        assertTrue(day.mailLate)
        assertEquals(0, day.chats)
        assertNull(day.questions)
        assertTrue(day.card.mail.isEmpty())
        val none = Briefing.day(db, "~zod", null, null, null, { zone }, twentyFour = false, name = { it }, seen = CardSeen())
        assertFalse(none.mailLate, "no mail app is not late mail")
        assertNull(none.firstEvent)
        assertTrue(none.card.isEmpty)
    }
}
