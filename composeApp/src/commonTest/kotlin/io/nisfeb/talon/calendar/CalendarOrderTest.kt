package io.nisfeb.talon.calendar

import kotlin.test.Test
import kotlin.test.assertEquals

/** The calendars in the owner's order, where one was made. */
class CalendarOrderTest {
    private val ship = listOf("birthday", "default", "work", "zoo").map { CalendarInfo(it, it) }
    private fun ids(order: List<String>) = orderCalendars(ship, order).map { it.id }

    @Test
    fun `with no order made, the ship's own stands`() {
        assertEquals(listOf("birthday", "default", "work", "zoo"), ids(emptyList()))
    }

    @Test
    fun `the owner's order leads, and what it does not name follows as the ship gave it`() {
        assertEquals(listOf("work", "default", "birthday", "zoo"), ids(listOf("work", "default", "birthday", "zoo")))
        // A calendar made since the order was: after the placed ones, not lost.
        assertEquals(listOf("work", "birthday", "default", "zoo"), ids(listOf("work")))
    }

    @Test
    fun `a calendar that is gone is passed over`() {
        assertEquals(listOf("zoo", "default", "birthday", "work"), ids(listOf("gone", "zoo", "default")))
    }
}
