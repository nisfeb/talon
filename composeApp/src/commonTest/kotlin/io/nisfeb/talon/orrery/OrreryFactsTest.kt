package io.nisfeb.talon.orrery

import io.nisfeb.talon.calendar.CalendarRow
import io.nisfeb.talon.data.ContactEntity
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.mail.InboxEntry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The structural pipe is pure functions from Talon's rows to orrery's
 * wire shape. These pin the parts that would silently make duplicates
 * or bad ids on the ship: the day coarsening, the slugs, the source
 * pointers, the batch caps and their order.
 */
class OrreryFactsTest {
    private val me = "~zod"
    private val noon = 1_789_646_400_000L // 2026-09-17T12:00:00Z
    private val evening = noon + 6 * 3_600_000

    private fun post(author: String, whom: String, at: Long, id: String = "170") =
        MessageEntity(whom = whom, id = id, author = author, sentMs = at, contentJson = "[]", kind = "chat")

    @Test
    fun `a person is their patp without the sig`() {
        assertEquals("person/sampel-palnet", personId("~sampel-palnet"))
        assertEquals("person/sampel-palnet-sampel-palnet--sampel-palnet-sampel-palnet", personId("~sampel-palnet-sampel-palnet--sampel-palnet-sampel-palnet"))
    }

    @Test
    fun `two messages on one day are one last-contact claim`() {
        val a = messageFacts(post("~bus", "~bus", noon), me)!!
        val b = messageFacts(post("~bus", "~bus", evening, id = "171"), me)!!
        assertEquals("person/bus", a.subject)
        assertEquals(JsonPrimitive("2026-09-17"), a.value)
        assertEquals(a.value, b.value)
        assertEquals(a.atMs, b.atMs, "asserted at the start of the day, so the id ignores the hour")
        assertEquals("talon-dm", a.sourceKind)
        assertEquals("talon://chat/~bus?id=170", a.sourceId)
    }

    @Test
    fun `our own posts and channel posts are told apart`() {
        assertNull(messageFacts(post(me, "~bus", noon), me))
        val chan = messageFacts(post("~bus", "chat/~host/general", noon), me)!!
        assertEquals("talon-chat", chan.sourceKind)
        assertEquals("talon://chat/chat/~host/general?id=170", chan.sourceId)
    }

    @Test
    fun `mail names everyone on the thread but us and never the future`() {
        val e = InboxEntry(id = "t1", last = evening + 48 * 3_600_000, participants = listOf(me, "~bus", "~nec", "bad"))
        val obs = mailFacts(e, me, nowMs = evening)
        assertEquals(listOf("person/bus", "person/nec"), obs.map { it.subject })
        assertEquals(JsonPrimitive("2026-09-17"), obs[0].value, "capped to now: the author's clock is theirs")
        assertEquals("talon://mail/t1", obs[0].sourceId)
    }

    @Test
    fun `a contact is a body with every handle as an alias`() {
        val c = ContactEntity(ship = "~bus", nickname = "Bus", bio = null, avatarUrl = null, status = "at the shop", statusUpdatedMs = noon)
        val f = contactFacts(c, me, handle = "~bus", longHandle = null)
        assertEquals(OBody("person/bus", "Bus", listOf("Bus", "~bus")), f.bodies.single())
        val status = f.observations.single()
        assertEquals("status", status.attr)
        assertEquals(JsonPrimitive("at the shop"), status.value)
        assertEquals(noon, status.atMs)
        assertEquals("person/me", contactFacts(c.copy(ship = me), me, "~zod", null).bodies.single().id)
    }

    @Test
    fun `an event is a situation under way between its ends, and where we are`() {
        val row = CalendarRow(id = "E1", cal = "default", meta = buildJsonObject { put("name", JsonPrimitive("Dentist")); put("location", JsonPrimitive("Main St")) }, l = noon, r = evening)
        val f = eventFacts(row, me)
        assertEquals("situation/cal-default-e1", f.bodies.single().id)
        val status = f.observations.first { it.attr == "status" }
        assertEquals(noon, status.atMs)
        assertEquals(evening, status.untilMs)
        val where = f.observations.first { it.subject == "person/me" }
        assertEquals("location", where.attr)
        assertEquals(60, where.conf)
        assertEquals(evening, where.untilMs)
        assertEquals("default/E1", where.sourceId)
        assertTrue(eventFacts(row.copy(cat = "todo"), me).observations.isEmpty(), "tasks stay in the calendar")
    }

    @Test
    fun `a published call is a situation with its speakers and a pointer to the words`() {
        val f = callFacts("urb://~zod/lattice/calls/1", "Standup", setOf(me, "~bus", "~nec"), me, noon) { if (it == "~bus") "Bus" else it }
        assertEquals(listOf("situation/call-1789646400", "person/bus", "person/nec"), f.bodies.map { it.id })
        assertEquals(listOf("~bus", "Bus"), f.bodies[1].aliases)
        val refs = f.observations.filter { it.attr == "participants" }.map { it.value.jsonObject["ref"]!!.jsonPrimitive.content }
        assertEquals(listOf("person/me", "person/bus", "person/nec"), refs)
        assertEquals("urb://~zod/lattice/calls/1", f.observations.first { it.attr == "transcript" }.value.jsonPrimitive.content)
        assertEquals(2, f.observations.count { it.attr == "last-contact" })
        assertTrue(f.observations.all { it.sourceKind == "talon-call" && it.sourceId == "urb://~zod/lattice/calls/1" })
    }

    @Test
    fun `a situation slug is bounded and clean`() {
        val row = CalendarRow(id = "A".repeat(80) + "!!", cal = "Work Cal", meta = buildJsonObject { put("name", JsonPrimitive("x")) }, l = 1, r = 2, idx = 3)
        val id = situationId(row).removePrefix("situation/")
        assertTrue(id.length <= 64, id)
        assertTrue(id.matches(Regex("[a-z0-9-]+")), id)
        assertTrue(id.startsWith("cal-work-cal-"))
    }

    @Test
    fun `batches send bodies first and stay under the caps`() {
        val bodies = (1..120).map { OBody("person/p$it") }
        val obs = (1..450).map { Obs("person/p1", "a", JsonPrimitive(it), it.toLong(), sourceKind = "t", sourceId = "$it") }
        val out = batches(Facts(bodies, obs))
        assertEquals(3 + 3, out.size)
        out.take(3).forEach { assertTrue(it.jsonObject["observations"]!!.jsonArray.isEmpty()) }
        out.drop(3).forEach { assertTrue(it.jsonObject["bodies"]!!.jsonArray.isEmpty()) }
        assertEquals(listOf(50, 50, 20), out.take(3).map { it.jsonObject["bodies"]!!.jsonArray.size })
        assertEquals(listOf(200, 200, 50), out.drop(3).map { it.jsonObject["observations"]!!.jsonArray.size })
        val one: JsonObject = out[3].jsonObject["observations"]!!.jsonArray[0].jsonObject
        assertEquals("1970-01-01T00:00:00.001Z", one["at"]!!.jsonPrimitive.content)
        assertEquals("t", one["source"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
    }
}
