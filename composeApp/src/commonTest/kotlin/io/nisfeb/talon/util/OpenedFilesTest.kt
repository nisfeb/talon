package io.nisfeb.talon.util

import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The files Talon hands to another app: in its own cache, and dropped after a day. */
class OpenedFilesTest {
    @Test fun `an event file is written under talon's cache`() {
        val uri = createTempFileUri("talon-event-", ".ics", "BEGIN:VCALENDAR")
        val path = uri.removePrefix("file://").toPath()
        assertEquals(openedFilesDir().toPath(), path.parent)
        assertEquals("BEGIN:VCALENDAR", FileSystem.SYSTEM.read(path) { readUtf8() })
        assertTrue(cacheDirPath.toPath() in generateSequence(path) { it.parent }.toList())
    }

    @Test fun `files changed before the cutoff go and newer ones stay`() {
        val dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "talon-opened-${nowMs()}"
        FileSystem.SYSTEM.createDirectories(dir)
        try {
            FileSystem.SYSTEM.write(dir / "a.ics") { writeUtf8("a") }
            pruneOlderThan(dir, nowMs() - 60_000)
            assertEquals(listOf("a.ics"), FileSystem.SYSTEM.list(dir).map { it.name })
            pruneOlderThan(dir, nowMs() + 60_000)
            assertEquals(emptyList(), FileSystem.SYSTEM.list(dir))
        } finally {
            FileSystem.SYSTEM.deleteRecursively(dir)
        }
    }
}
