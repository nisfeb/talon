package io.nisfeb.talon.ui

import io.nisfeb.talon.ui.screens.LoginLapses
import io.nisfeb.talon.ui.screens.lapsedLoginNotice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A lapsed sign-in is held until a shell takes it, once: a screen that
 * comes up after the lapse still sees it, and Cancel on the form it
 * opened does not open it again.
 */
class LoginLapsesTest {
    @Test
    fun `a lapse is taken once, for its own ship`() {
        LoginLapses.lapsed("~nec")
        LoginLapses.lapsed("~nec")
        assertFalse(LoginLapses.take("~bud"))
        assertTrue(LoginLapses.take("~nec"))
        assertFalse(LoginLapses.take("~nec"), "said once")
        assertFalse("~nec" in LoginLapses.ships.value)
        assertEquals("You were signed out of ~nec. Sign in again to keep getting its messages.", lapsedLoginNotice("~nec"))
        // Review B1-12: a comet by its word name, not its full @p.
        val comet = "~doznec-binwes-samper-siglet--fidpen-sogdur-wacser-wissun"
        assertEquals("You were signed out of ${shipHandle(comet)}. Sign in again to keep getting its messages.", lapsedLoginNotice(comet))
        assertTrue(comet !in lapsedLoginNotice(comet))
    }
}
