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
        assertTrue(calendarRow(CalendarAvailability.ABSENT, null, grubbery = false).canInstall)
        assertTrue(latticeRow(installed = false).canInstall)
        assertTrue(groupsRow(installed = false).canInstall)
    }

    // Groups is its own desk from its own publisher, so its row installs
    // something different from the three that ride in Grubbery.
    @Test
    fun `groups installs its own desk, the rest come with grubbery`() {
        assertEquals(AppInstall.GROUPS, groupsRow(installed = false).install)
        assertEquals(AppInstall.GRUBBERY, latticeRow(installed = false).install)
        assertEquals(AppInstall.GRUBBERY, mailRow(MailAvailability.NO_GRUBBERY, null).install)
        assertEquals(AppInstall.GRUBBERY, calendarRow(CalendarAvailability.ABSENT, null, grubbery = false).install)
        assertFalse(groupsRow(installed = true).canInstall)
        assertFalse(groupsRow(installed = null).canInstall)
    }

    // The calendar ships inside Grubbery, so its absence is a statement
    // about the desk, and only one of the two readings is installable.
    @Test
    fun `a calendar missing from a grubbery that is here is out of date`() {
        val row = calendarRow(CalendarAvailability.ABSENT, null, grubbery = true)
        assertEquals(AppState.OUTDATED, row.state)
        assertFalse(row.canInstall)
    }

    @Test
    fun `a calendar is offered nothing until we know about grubbery`() {
        val row = calendarRow(CalendarAvailability.ABSENT, null, grubbery = null)
        assertEquals(AppState.UNKNOWN, row.state)
        assertFalse(row.canInstall)
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
        assertEquals(AppState.SIGNED_OUT, calendarRow(CalendarAvailability.SIGNED_OUT, null, grubbery = true).state)
        assertEquals(AppState.UNKNOWN, latticeRow(installed = null).state)
        assertFalse(mailRow(MailAvailability.SIGNED_OUT, null).canInstall)
        assertFalse(latticeRow(installed = null).canInstall)
    }

    @Test
    fun `a working app carries the ship's last words anyway`() {
        val row = calendarRow(CalendarAvailability.PRESENT, "the ship did not answer", grubbery = true)
        assertEquals(AppState.WORKING, row.state)
        assertEquals("the ship did not answer", row.error)
    }

    // Armillary, like orrery, is installed from the ship's own Grubbery
    // shell, so its row never offers an install: it says where to go.
    @Test
    fun `armillary is never offered an install, whatever it is doing`() {
        val here = armillaryRow(io.nisfeb.talon.armillary.ArmillaryAvailability.PRESENT)
        assertEquals(AppState.WORKING, here.state)
        assertEquals("Answering on this ship.", here.detail)
        val gone = armillaryRow(io.nisfeb.talon.armillary.ArmillaryAvailability.MISSING, "404")
        assertEquals(AppState.MISSING, gone.state)
        assertFalse(gone.canInstall)
        assertEquals("404", gone.error)
        assertEquals(AppState.SIGNED_OUT, armillaryRow(io.nisfeb.talon.armillary.ArmillaryAvailability.SIGNED_OUT).state)
        assertEquals(AppState.UNKNOWN, armillaryRow(io.nisfeb.talon.armillary.ArmillaryAvailability.UNKNOWN).state)
        assertTrue(listOf(
            io.nisfeb.talon.armillary.ArmillaryAvailability.PRESENT,
            io.nisfeb.talon.armillary.ArmillaryAvailability.MISSING,
            io.nisfeb.talon.armillary.ArmillaryAvailability.SIGNED_OUT,
            io.nisfeb.talon.armillary.ArmillaryAvailability.UNKNOWN,
        ).none { armillaryRow(it).canInstall })
    }

    @Test
    fun `the permits page hangs off the ship, however its url was written`() {
        assertEquals("https://ship.example/apps/grubbery/permits", permitsUrl("https://ship.example"))
        assertEquals("https://ship.example/apps/grubbery/permits", permitsUrl("https://ship.example/"))
    }
}
