package io.nisfeb.talon.orrery

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What notifies when proposals arrive, and what the beacon stream says. */
class ActionNotificationsTest {
    private fun a(id: String, status: String = "proposed", why: String? = null) = OrreryAction(
        id, "task", "Buy swim goggles",
        if (why != null) JsonObject(mapOf("why" to JsonPrimitive(why))) else JsonObject(emptyMap()),
        emptyList(), null, status, "generator",
    )

    @Test
    fun `the first read of a session only sets the baseline`() {
        val n = diffActionNotifications(listOf(a("p1"), a("p2")), lastSeen = null)
        assertTrue(n.raise.isEmpty(), "what has waited for days is not replayed")
        assertEquals(setOf("p1", "p2"), n.seen)
    }

    @Test
    fun `a new proposal notifies, and an approved action does not`() {
        val n = diffActionNotifications(listOf(a("p1"), a("p2", why = "Lessons are today."), a("ok", status = "approved")), lastSeen = setOf("p1"))
        assertEquals(listOf(ActionNotification("p2", "Buy swim goggles", "task, proposed by generator. Lessons are today.")), n.raise)
        assertTrue(n.clear.isEmpty())
    }

    @Test
    fun `one answered anywhere is taken back`() {
        val n = diffActionNotifications(listOf(a("p1", status = "approved")), lastSeen = setOf("p1", "p2"))
        assertEquals(setOf("p1", "p2"), n.clear, "approved here, dismissed elsewhere: neither waits any more")
        assertTrue(n.seen.isEmpty())
    }

    @Test
    fun `a burst says how many more`() {
        val n = diffActionNotifications((1..5).map { a("p$it") }, lastSeen = emptySet(), cap = 3)
        assertEquals(listOf("p1", "p2", "p3", ""), n.raise.map { it.id })
        assertEquals("and 2 more waiting for you", n.raise.last().title)
    }

    @Test
    fun `the beacon reader answers each revision as its event ends`() {
        val r = BeaconReader()
        // As the ship sent it: the first event carries the revision as it stands.
        assertNull(r.feed("id: 1.549"))
        assertNull(r.feed("event: old /rev"))
        assertNull(r.feed("data: 1789826786662"))
        assertEquals("1789826786662", r.feed(""))
        // A keep-alive or another event carries no revision.
        assertNull(r.feed(": ping"))
        assertNull(r.feed(""))
        r.feed("event: /rev"); r.feed("data: 1789826790001")
        assertEquals("1789826790001", r.feed(""))
    }
}
