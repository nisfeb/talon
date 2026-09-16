package io.nisfeb.talon.ui

import io.nisfeb.talon.calendar.CalendarAvailability
import io.nisfeb.talon.mail.MailAvailability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Apps page offers the install only where installing is the fix. */
class GrubberyAppsTest {

    @Test
    fun `a missing app offers the install`() {
        assertTrue(mailRow(MailAvailability.NO_GRUBBERY, null).canInstall)
        assertTrue(calendarRow(CalendarAvailability.ABSENT, null).canInstall)
        assertTrue(latticeRow(installed = false).canInstall)
    }

    @Test
    fun `an out-of-date grubbery is not offered an install`() {
        val row = mailRow(MailAvailability.OLD_GRUBBERY, null)
        assertEquals(AppState.OUTDATED, row.state)
        // Kiln tracks the publisher, so a re-install fixes nothing here.
        assertFalse(row.canInstall)
    }

    @Test
    fun `being signed out is not an app problem, and neither is silence`() {
        assertEquals(AppState.SIGNED_OUT, mailRow(MailAvailability.SIGNED_OUT, null).state)
        assertEquals(AppState.SIGNED_OUT, calendarRow(CalendarAvailability.SIGNED_OUT, null).state)
        assertEquals(AppState.UNKNOWN, latticeRow(installed = null).state)
        assertFalse(mailRow(MailAvailability.SIGNED_OUT, null).canInstall)
        assertFalse(latticeRow(installed = null).canInstall)
    }

    @Test
    fun `a working app carries the ship's last words anyway`() {
        val row = calendarRow(CalendarAvailability.PRESENT, "the ship did not answer")
        assertEquals(AppState.WORKING, row.state)
        assertEquals("the ship did not answer", row.error)
    }

    @Test
    fun `the permits page hangs off the ship, however its url was written`() {
        assertEquals("https://ship.example/grubbery/permits", permitsUrl("https://ship.example"))
        assertEquals("https://ship.example/grubbery/permits", permitsUrl("https://ship.example/"))
    }
}
