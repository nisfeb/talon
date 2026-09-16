package io.nisfeb.talon.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopUpdateFlavorTest {
    @Test fun `an AppImage knows itself whatever the OS says`() {
        assertEquals(DesktopUpdateFlavor.AppImage, desktopUpdateFlavor("/home/x/Talon.AppImage", "Linux"))
    }

    @Test fun `packaged installs go by OS`() {
        assertEquals(DesktopUpdateFlavor.Deb, desktopUpdateFlavor(null, "Linux"))
        assertEquals(DesktopUpdateFlavor.Dmg, desktopUpdateFlavor("", "Mac OS X"))
        assertEquals(DesktopUpdateFlavor.Msi, desktopUpdateFlavor(null, "Windows 11"))
        assertNull(desktopUpdateFlavor(null, "FreeBSD"))
    }
}
