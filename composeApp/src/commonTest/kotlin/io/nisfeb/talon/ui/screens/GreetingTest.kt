package io.nisfeb.talon.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GreetingTest {

    private fun at(hour: Int, minute: Int = 0) = timeOfDayGreeting(hour * 60 + minute)

    @Test
    fun `it says what time of day it is`() {
        assertEquals("Good morning", at(9))
        assertEquals("Good afternoon", at(14))
        assertEquals("Good evening", at(19))
    }

    @Test
    fun `the small hours get their own`() {
        // "Good evening" at three in the morning reads as a machine
        // that has not looked at the time.
        assertEquals("Good night", at(3))
        assertEquals("Good night", at(0))
        assertEquals("Good night", at(23))
    }

    @Test
    fun `the boundaries land where people put them`() {
        assertEquals("Good night", at(4, 59))
        assertEquals("Good morning", at(5, 0))
        assertEquals("Good morning", at(11, 59))
        assertEquals("Good afternoon", at(12, 0))
        assertEquals("Good afternoon", at(16, 59))
        assertEquals("Good evening", at(17, 0))
        assertEquals("Good evening", at(21, 59))
        assertEquals("Good night", at(22, 0))
    }

    @Test
    fun `every minute of the day gets a greeting`() {
        for (m in 0 until 1440) {
            assertTrue(timeOfDayGreeting(m).isNotBlank(), "nothing to say at minute $m")
        }
    }

    @Test
    fun `a minute outside the day still reads as a time`() {
        // Arithmetic upstream can hand this a wrapped or negative
        // minute; a greeting is not the place to throw.
        assertEquals(at(1), timeOfDayGreeting(1 * 60 + 1440))
        assertEquals(at(23), timeOfDayGreeting(23 * 60 - 1440))
    }
}
