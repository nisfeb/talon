package io.nisfeb.talon.update

import kotlin.test.Test
import kotlin.test.assertEquals

class UpdateFileNameTest {

    @Test
    fun `a bare dot-dot segment falls back instead of naming the directory`() {
        // Strip first, blank-check second: the other order resolves to
        // the updates directory itself.
        assertEquals("talon-update.apk", sanitizedUpdateFileName("https://x/..", "talon-update.apk"))
    }

    @Test
    fun `parent references and separators cannot escape`() {
        assertEquals("evil", sanitizedUpdateFileName("https://x/../../evil", "fb"))
        assertEquals("__evil", sanitizedUpdateFileName("https://x/a/..\\..\\evil", "fb"))
    }

    @Test
    fun `a trailing slash falls back`() {
        assertEquals("fb", sanitizedUpdateFileName("https://x/releases/", "fb"))
    }

    @Test
    fun `a normal name passes through`() {
        assertEquals("Talon-x86_64.AppImage", sanitizedUpdateFileName("https://x/Talon-x86_64.AppImage", "fb"))
    }
}
