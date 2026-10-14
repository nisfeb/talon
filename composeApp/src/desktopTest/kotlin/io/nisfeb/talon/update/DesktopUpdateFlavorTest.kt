package io.nisfeb.talon.update

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test fun `the windows waiter runs the msi after we exit and reopens us`() {
        val cmd = windowsUpdateCommand(
            4242,
            "C:\\Users\\a\\AppData\\Local\\Talon\\updates\\Talon-1.8.26.msi",
            "C:\\Program Files\\Talon\\Talon.exe",
        )
        assertEquals("powershell", cmd.first())
        val script = cmd.last()
        // waits for THIS process to be gone before touching any file
        assertContains(script, "Wait-Process -Id 4242")
        // runs the installer, and never lets it reboot
        assertContains(script, "Start-Process msiexec")
        assertContains(script, "'/i','C:\\Users\\a\\AppData\\Local\\Talon\\updates\\Talon-1.8.26.msi','/norestart'")
        // the msi waits, so the relaunch happens after the install
        assertTrue(script.indexOf("Start-Process msiexec") < script.indexOf("Start-Process -FilePath"))
        assertContains(script, "-Wait")
        // reopens the upgraded launcher
        assertContains(script, "Start-Process -FilePath 'C:\\Program Files\\Talon\\Talon.exe'")
    }

    @Test fun `a single quote in a path is doubled for powershell`() {
        val cmd = windowsUpdateCommand(1, "C:\\o'brien\\up.msi", "C:\\o'brien\\Talon.exe")
        assertContains(cmd.last(), "C:\\o''brien\\up.msi")
        assertContains(cmd.last(), "C:\\o''brien\\Talon.exe")
    }
}
