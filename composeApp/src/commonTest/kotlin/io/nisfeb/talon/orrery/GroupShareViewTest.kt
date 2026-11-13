package io.nisfeb.talon.orrery

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A group's Info beside shares already running. One share with a member
 * hid "Share my location with the group" (2026-11-10), and a group share
 * replaced a member's own exact share with the group's.
 */
class GroupShareViewTest {
    private fun out(ship: String, group: Boolean) = OutShare(ship, 1L, home = false, exact = !group, group = group)
    private val others = listOf("~bus", "~nec", "~fed")

    @Test
    fun `nothing running offers the whole group`() {
        assertEquals(GroupShareView(emptyList(), emptyList(), others, offer = true), groupShareView(others, emptyList()))
    }

    @Test
    fun `a member's own share is left out of the group's, and the group is still offered`() {
        val v = groupShareView(others, listOf(out("~bus", group = false), out("~zod", group = true)))
        assertEquals(GroupShareView(emptyList(), listOf("~bus"), listOf("~nec", "~fed"), offer = true), v)
    }

    @Test
    fun `a group share is what Stop ends, and with everyone covered nothing more is offered`() {
        val v = groupShareView(others, listOf(out("~bus", group = false), out("~nec", group = true), out("~fed", group = true)))
        assertEquals(GroupShareView(listOf("~nec", "~fed"), listOf("~bus"), listOf("~nec", "~fed"), offer = false), v)
    }

    @Test
    fun `a member who joined since is offered the share, with the group's others`() {
        val v = groupShareView(others, listOf(out("~nec", group = true)))
        assertEquals(GroupShareView(listOf("~nec"), emptyList(), others, offer = true), v)
    }
}
