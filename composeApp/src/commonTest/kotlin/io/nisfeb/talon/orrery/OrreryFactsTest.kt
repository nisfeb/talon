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
        val a = messageFacts(post("~bus", "~bus", noon), me, "person/bus")!!
        val b = messageFacts(post("~bus", "~bus", evening, id = "171"), me, "person/bus")!!
        assertEquals("person/bus", a.subject)
        assertEquals(JsonPrimitive("2026-09-17"), a.value)
        assertEquals(a.value, b.value)
        assertEquals(a.atMs, b.atMs, "asserted at the start of the day, so the id ignores the hour")
        assertEquals("talon-dm", a.sourceKind)
        assertEquals("talon://chat/~bus?id=170", a.sourceId)
    }

    @Test
    fun `our own posts and channel posts are told apart`() {
        assertNull(messageFacts(post(me, "~bus", noon), me, "person/me"))
        val chan = messageFacts(post("~bus", "chat/~host/general", noon), me, "person/bus")!!
        assertEquals("talon-chat", chan.sourceKind)
        assertEquals("talon://chat/chat/~host/general?id=170", chan.sourceId)
    }

    @Test
    fun `mail names everyone on the thread but us and never the future`() {
        val e = InboxEntry(id = "t1", last = evening + 48 * 3_600_000, participants = listOf(me, "~bus", "~nec", "bad"))
        val obs = mailFacts(e, me, nowMs = evening) { ship -> personId(ship) }
        assertEquals(listOf("person/bus", "person/nec"), obs.map { it.subject })
        assertEquals(JsonPrimitive("2026-09-17"), obs[0].value, "capped to now: the author's clock is theirs")
        assertEquals("talon://mail/t1", obs[0].sourceId)
    }

    @Test
    fun `a contact is a body with every handle as an alias, on the id the pass decided`() {
        val c = ContactEntity(ship = "~bus", nickname = "Bus", bio = null, avatarUrl = null, status = "at the shop", statusUpdatedMs = noon)
        // The ship may already keep them as person/sarah; the pass says
        // which body this is, and the body just says what it looks like.
        assertEquals(OBody("person/sarah", "Bus", listOf("Bus", "~bus")), personBody(c, "person/sarah", handle = "~bus", longHandle = null))
        // The status line is text to be read, not a fact to be sent:
        // Tlon's field is social, and a joke is not a circumstance.
        assertEquals("at the shop" to noon, contactStatus(c))
        assertEquals(null, contactStatus(c.copy(status = null)))
        assertEquals(null, contactStatus(c.copy(statusUpdatedMs = null)), "a line with no time says nothing")
    }


    @Test
    fun `a body the ship has learns the names it lacks, and is never remade`() {
        val body = OBody("person/bus", "Buster", listOf("Buster", "~bus", "bus"))
        // Nothing to say: the ship answers to all of them already.
        assertTrue(teachNames(body, setOf("Buster", "~bus", "bus")).isEmpty())
        // A new nickname goes up as an alias with no name of its own, so
        // the ship keeps whatever the owner called them.
        val taught = teachNames(body, setOf("~bus")).single()
        assertEquals(null, taught.name)
        assertEquals(listOf("Buster", "bus"), taught.aliases)
        // No such body: made whole, or left alone where a pass may not make one.
        assertEquals(listOf(body), teachNames(body, null))
        assertTrue(teachNames(body, null, make = false).isEmpty(), "names alone would come back hollow")
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
