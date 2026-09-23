package io.nisfeb.talon.orrery

import io.nisfeb.talon.calendar.CalendarRow
import io.nisfeb.talon.data.ContactEntity
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

    @Test
    fun `a person is their patp without the sig`() {
        assertEquals("person/sampel-palnet", personId("~sampel-palnet"))
        assertEquals("person/sampel-palnet-sampel-palnet--sampel-palnet-sampel-palnet", personId("~sampel-palnet-sampel-palnet--sampel-palnet-sampel-palnet"))
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
        // No such body: made whole.
        assertEquals(listOf(body), teachNames(body, null))
    }

    @Test
    fun `a published call is a situation with its speakers and a pointer to the words`() {
        val f = callFacts("urb://~zod/lattice/calls/1", "Standup", setOf(me, "~bus", "~nec"), me, noon, nameFor = { if (it == "~bus") "Bus" else it })
        assertEquals(listOf("situation/call-1789646400", "person/bus", "person/nec"), f.bodies.map { it.id })
        assertEquals(listOf("~bus", "Bus"), f.bodies[1].aliases)
        val refs = f.observations.filter { it.attr == "participants" }.map { it.value.jsonObject["ref"]!!.jsonPrimitive.content }
        assertEquals(listOf("person/me", "person/bus", "person/nec"), refs)
        assertEquals("urb://~zod/lattice/calls/1", f.observations.first { it.attr == "transcript" }.value.jsonPrimitive.content)
        // On the people, never on a bare @p the ship has no body for.
        assertEquals(listOf("person/bus", "person/nec"), f.observations.filter { it.attr == "last-contact" }.map { it.subject })
        assertTrue(f.observations.all { it.sourceKind == "talon-call" && it.sourceId == "urb://~zod/lattice/calls/1" })
    }

    @Test
    fun `a speaker the ship keeps under another name is that body, taught the handle`() {
        val f = callFacts("urb://~zod/lattice/calls/1", "Standup", setOf("~bus"), me, noon, nameFor = { "Bus" }) { ship ->
            if (ship == "~bus") "person/buster" else personId(ship)
        }
        assertEquals(listOf("situation/call-1789646400", "person/buster"), f.bodies.map { it.id })
        assertEquals(null, f.bodies[1].name, "aliases alone: the ship keeps its own name")
        assertEquals(listOf("person/buster"), f.observations.filter { it.attr == "last-contact" }.map { it.subject })
        assertEquals(
            listOf("person/me", "person/buster"),
            f.observations.filter { it.attr == "participants" }.map { it.value.jsonObject["ref"]!!.jsonPrimitive.content },
        )
    }

    // The ship answers a batch before its writer applies it and counts a
    // batch's own bodies as known: a fact sent in the batch after its
    // body's was refused as about an unknown subject.
    @Test
    fun `a body goes with the facts about it, and batches stay under the caps`() {
        val bodies = (1..120).map { OBody("person/p$it") }
        val obs = (1..450).map { Obs("person/p1", "a", JsonPrimitive(it), it.toLong(), sourceKind = "t", sourceId = "$it") } +
            Obs("person/known", "a", JsonPrimitive(0), 1L, sourceKind = "t", sourceId = "k")
        val out = batches(Facts(bodies, obs))
        fun sizes(k: String) = out.map { it.jsonObject[k]!!.jsonArray.size }
        assertTrue(sizes("bodies").max() <= MAX_BODIES && sizes("observations").max() <= MAX_OBS)
        // Every fact about a new body rides in a batch that carries it.
        out.forEach { b ->
            val carried = b.jsonObject["bodies"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()
            b.jsonObject["observations"]!!.jsonArray.map { it.jsonObject["subject"]!!.jsonPrimitive.content }
                .filter { it.startsWith("person/p") }
                .forEach { assertTrue(it in carried, "$it sent without its body") }
        }
        assertEquals(listOf(1, 1, 50, 50, 20, 0), sizes("bodies"), "p1 again with each batch of its facts, then the rest fifty at a time")
        assertEquals(listOf(200, 200, 50, 0, 0, 1), sizes("observations"))
        assertEquals(451, sizes("observations").sum(), "and every fact once")
        val one: JsonObject = out[0].jsonObject["observations"]!!.jsonArray[0].jsonObject
        assertEquals("1970-01-01T00:00:00.001Z", one["at"]!!.jsonPrimitive.content)
        assertEquals("t", one["source"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
    }
}
