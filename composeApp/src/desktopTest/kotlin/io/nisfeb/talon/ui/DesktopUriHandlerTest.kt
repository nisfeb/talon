package io.nisfeb.talon.ui

import io.nisfeb.talon.util.createTempFileUri
import io.nisfeb.talon.util.openedFilesDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the desktop hands to xdg-open, open or rundll32. */
class DesktopUriHandlerTest {
    private val opened = File(openedFilesDir())

    @Test fun `web and mail links are opened as they are`() {
        for (uri in listOf("https://example.com/a?b=c", "http://example.com", "HTTPS://Example.com", "mailto:me@example.com")) {
            assertEquals(uri, openTarget(uri, opened))
        }
    }

    @Test fun `other kinds of link are refused`() {
        for (uri in listOf(
            "file:///etc/passwd", "file:///home/me/Downloads/run.desktop", "smb://host/share", "ssh://host",
            "javascript:alert(1)", "vscode://file/x", "magnet:?xt=urn:btih:x", "talon://chat/~zod", "urb://~zod/x",
            "-v", "/etc/passwd", "", "tel:+15551234",
        )) {
            assertNull(openTarget(uri, opened), uri)
        }
    }

    @Test fun `an event file talon made is opened as a file`() {
        val uri = createTempFileUri("talon-event-", ".ics", "BEGIN:VCALENDAR\nEND:VCALENDAR\n")
        val target = openTarget(uri, opened)
        assertTrue(target is File && target.isFile && target.parentFile == opened.canonicalFile, "$target")
    }

    @Test fun `a file beside or above talon's own is refused`() {
        assertNull(openTarget(File(opened.parentFile, "x.ics").toURI().toString(), opened))
        assertNull(openTarget("file://${opened.path}/../x.ics", opened))
        assertNull(openTarget("file://${opened.path}/sub/x.ics", opened))
    }

    // They piled up in the shared temp directory, readable by anyone.
    @Test fun `event files older than a day are dropped on the next and newer ones stay`() {
        opened.mkdirs()
        val old = File(opened, "talon-event-old.ics").apply { writeText("old"); setLastModified(System.currentTimeMillis() - 2 * 86_400_000L) }
        val recent = File(opened, "talon-event-recent.ics").apply { writeText("recent"); setLastModified(System.currentTimeMillis() - 60_000L) }
        val made = openTarget(createTempFileUri("talon-event-", ".ics", "new"), opened) as File
        assertTrue(!old.exists() && recent.isFile && made.isFile)
    }
}
