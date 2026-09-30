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
        assertTrue(groupsRow(installed = false).canInstall)
    }

    // Groups is its own desk from its own publisher, so its row installs
    // something different from the three that ride in Grubbery.
    @Test
    fun `groups installs its own desk, the rest come with grubbery`() {
        assertEquals(AppInstall.GROUPS, groupsRow(installed = false).install)
        assertEquals(AppInstall.GRUBBERY, latticeRow(installed = false).install)
        assertEquals(AppInstall.GRUBBERY, mailRow(MailAvailability.NO_GRUBBERY, null).install)
        assertEquals(AppInstall.GRUBBERY, calendarRow(CalendarAvailability.ABSENT, null).install)
        assertFalse(groupsRow(installed = true).canInstall)
        assertFalse(groupsRow(installed = null).canInstall)
    }

    // Mail and the calendar are stock desks of the Grubbery shell: a ship
    // that has the shell and not the desk is missing it, and the install
    // fetches it. They were called out of date and offered nothing, and
    // the user was told to wait for an update that never came.
    @Test
    fun `an app the shell has not fetched is offered the install`() {
        val mail = mailRow(MailAvailability.NOT_FETCHED, null)
        assertEquals(AppState.MISSING, mail.state)
        assertEquals(AppInstall.GRUBBERY, mail.install)
        assertEquals(AppState.MISSING, calendarRow(CalendarAvailability.ABSENT, null).state)
    }

    @Test
    fun `being signed out is not an app problem, and neither is silence`() {
        assertEquals(AppState.SIGNED_OUT, mailRow(MailAvailability.SIGNED_OUT, null).state)
        assertEquals(AppState.SIGNED_OUT, calendarRow(CalendarAvailability.SIGNED_OUT, null).state)
        assertEquals(AppState.UNKNOWN, latticeRow(installed = null).state)
        assertFalse(mailRow(MailAvailability.SIGNED_OUT, null).canInstall)
        assertFalse(latticeRow(installed = null).canInstall)
    }

    // Armillary, like orrery, is installed from the ship's own Grubbery
    // shell, so its row never offers an install: it says where to go.
    @Test
    fun `armillary is offered the shell's own add, and only where it is missing`() {
        val here = armillaryRow(io.nisfeb.talon.armillary.ArmillaryAvailability.PRESENT)
        assertEquals(AppState.WORKING, here.state)
        assertEquals("Answering on this ship.", here.detail)
        val gone = armillaryRow(io.nisfeb.talon.armillary.ArmillaryAvailability.MISSING, "404")
        assertEquals(AppState.MISSING, gone.state)
        // Not kiln's: a shell desk, added through an authenticated call
        // to the shell the ship already runs.
        assertEquals(AppInstall.ARMILLARY, gone.install)
        assertEquals("404", gone.error)
        assertEquals(AppState.SIGNED_OUT, armillaryRow(io.nisfeb.talon.armillary.ArmillaryAvailability.SIGNED_OUT).state)
        assertEquals(AppState.UNKNOWN, armillaryRow(io.nisfeb.talon.armillary.ArmillaryAvailability.UNKNOWN).state)
        // Everything but missing has nothing to add: a ship that is
        // answering has it, and one that is signed out cannot be asked.
        assertTrue(listOf(
            io.nisfeb.talon.armillary.ArmillaryAvailability.PRESENT,
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
