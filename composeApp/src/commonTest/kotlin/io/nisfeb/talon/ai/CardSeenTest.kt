package io.nisfeb.talon.ai

import io.nisfeb.talon.calendar.CalendarRow
import io.nisfeb.talon.calendar.CalendarTask
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.mail.InboxEntry
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a run's tools showed, made into its card: the dictation's and the Assistant's. */
class CardSeenTest {
    private val zone = TimeZone.of("America/New_York")
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime(2026, 10, d, h, m).toInstant(zone).toEpochMilliseconds()
    private val now = at(16, 12, 30)
    private val today = LocalDate(2026, 10, 16)
    private fun meta(name: String) = JsonObject(mapOf("name" to JsonPrimitive(name)))

    @Test
    fun `everything the tools showed is on the card by name and clock`() {
        val seen = CardSeen()
        seen.people(listOf(PersonMatch("~sampel-palnet", listOf("Andrea"))))
        seen.people(listOf(PersonMatch("~zod", listOf("~zod"))))
        seen.events(listOf(CalendarRow(id = "e", meta = meta("Core Sync"), l = at(16, 13), r = at(16, 14))), today, today)
        seen.tasks(listOf(CalendarTask(id = "t", meta = meta("Buy milk"))))
        seen.messages(listOf(MessageEntity("~zod", "1", "~zod", at(16, 9), """[{"inline":["hello"]}]""", "chat")))
        seen.mail(listOf(InboxEntry(id = "m", subject = "Lunch", from = "~zod", last = at(16, 8))))
        val card = seen.card(zone, { if (it == "~zod") "Zod" else it }, twentyFour = true, nowMs = now)
        assertEquals(listOf("~sampel-palnet", "~zod"), card.people.map { it.ship }, "people from every find, once each")
        assertEquals("13:00 – 14:00", card.days.single().events.single().time, "on the 24-hour clock asked for")
        assertEquals("Today", card.days.single().label)
        assertEquals(listOf("Buy milk"), card.tasks.map { it.name })
        assertEquals("Zod" to "hello", card.messages.single().let { it.author to it.text })
        assertEquals("09:00", card.messages.single().time)
        assertEquals("Zod", card.mail.single().from)
        assertEquals("1:00 PM – 2:00 PM", seen.card(zone, { it }, twentyFour = false, nowMs = now).days.single().events.single().time)
    }

    @Test
    fun `reset forgets it all for the next run`() {
        val seen = CardSeen()
        seen.people(listOf(PersonMatch("~zod", listOf("~zod"))))
        seen.events(listOf(CalendarRow(id = "e", meta = meta("Core Sync"), l = at(16, 13), r = at(16, 14))), today, today)
        seen.tasks(listOf(CalendarTask(id = "t", meta = meta("Buy milk"))))
        seen.messages(listOf(MessageEntity("~zod", "1", "~zod", at(16, 9), "", "chat")))
        seen.mail(listOf(InboxEntry(id = "m", subject = "Lunch", from = "~zod", last = at(16, 8))))
        assertTrue(!seen.card(zone, { it }, false, now).isEmpty)
        seen.reset()
        assertTrue(seen.card(zone, { it }, false, now).isEmpty)
    }
}
