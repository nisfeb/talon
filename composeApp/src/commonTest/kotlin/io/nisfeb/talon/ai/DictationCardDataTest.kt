package io.nisfeb.talon.ai

import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.mail.InboxEntry
import io.nisfeb.talon.orrery.OrreryAction
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class DictationCardDataTest {
    private val zone = TimeZone.of("America/New_York")
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime(2026, 10, d, h, m).toInstant(zone).toEpochMilliseconds()
    private val now = at(16, 12, 30)

    @Test
    fun `people once each, by the name they go by`() {
        val people = DictationCards.people(listOf(
            PersonMatch("~sampel-palnet", listOf("Andrea", "~sampel-palnet")),
            PersonMatch("~sampel-palnet", listOf("Andrea")),
            PersonMatch("~zod", listOf("~zod")),
        ))
        assertEquals(listOf("Andrea" to "~sampel-palnet", "~zod" to "~zod"), people.map { it.name to it.ship })
        assertEquals(mapOf("Andrea" to "~sampel-palnet", "~sampel-palnet" to "~sampel-palnet", "~zod" to "~zod"), DictationCard(people = people).names)
    }

    @Test
    fun `messages newest first, where and when, opening the message`() {
        fun msg(id: String, whom: String, ms: Long, parent: String? = null) = MessageEntity(whom = whom, id = id, author = "~zod", sentMs = ms, contentJson = "", kind = "chat", parentId = parent)
        val rows = DictationCards.messages(
            listOf(msg("1", "~sampel-palnet", at(15, 9)), msg("2", "chat/~host/general", at(16, 11, 5), parent = "p1")),
            name = { if (it == "~zod") "Zod" else "Andrea" }, text = { "hello ${it.id}" }, zone = zone, nowMs = now, twentyFour = false,
        )
        assertEquals(listOf("2", "1"), rows.map { it.text.removePrefix("hello ") })
        assertEquals("general", rows[0].where)
        assertEquals("11:05 AM", rows[0].time)
        assertEquals("Chat with Andrea", rows[1].where)
        assertEquals("Yesterday", rows[1].time)
        assertEquals(io.nisfeb.talon.urbit.TalonLink.forMessage("chat/~host/general", "2", "p1"), rows[0].link)
    }

    @Test
    fun `mail with who it is from, opening the thread`() {
        val rows = DictationCards.mail(
            listOf(InboxEntry(id = "t1", subject = "", from = "~zod", snippet = "line one\nline two", last = at(12, 8), unread = true)),
            name = { "Zod" }, zone = zone, nowMs = now, twentyFour = false,
        )
        assertEquals(CardMail("Zod", "(no subject)", "line one line two", "Oct 12", true, io.nisfeb.talon.urbit.TalonLink.forMail("t1")), rows.single())
    }

    private fun action(kind: String, payload: Map<String, String>, title: String = "T", due: String? = null) = OrreryAction(
        id = "a", kind = kind, title = title, payload = JsonObject(payload.mapValues { JsonPrimitive(it.value) }),
        about = emptyList(), due = due, status = "proposed", by = "owner",
    )

    @Test
    fun `a waiting message says to whom by name, and what`() {
        val p = DictationCards.proposal(action("message", mapOf("to" to "person/me", "text" to "Locked test")), zone, now, false)
        assertEquals(CardProposal("message", "T", to = "You", text = "Locked test"), p)
        assertEquals("Andrea Egan", DictationCards.bodyName("person/andrea-egan"))
    }

    @Test
    fun `a waiting event says when and where`() {
        val p = DictationCards.proposal(
            action("calendar", mapOf("title" to "Dentist", "starts" to "2026-10-20T19:00:00Z", "ends" to "2026-10-20T20:00:00Z", "location" to "Main St")),
            zone, now, false,
        )
        assertEquals(CardProposal("calendar", "Dentist", time = "Tuesday, Oct 20 · 3:00 PM – 4:00 PM", place = "Main St"), p)
    }

    @Test
    fun `a waiting task says when it is due`() {
        val p = DictationCards.proposal(action("task", emptyMap(), title = "Buy milk", due = "2026-10-17T16:00:00Z"), zone, now, false)
        assertEquals(CardProposal("task", "Buy milk", time = "Due Tomorrow"), p)
    }
}
