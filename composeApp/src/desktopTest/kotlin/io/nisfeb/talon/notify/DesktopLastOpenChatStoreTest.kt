package io.nisfeb.talon.notify

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopLastOpenChatStoreTest {

    private val tempFile = File.createTempFile("talon-last-open-test", ".json").also {
        // createTempFile creates an empty file; remove so the store
        // sees "no file yet" on first construction (the typical real path).
        it.delete()
    }

    @AfterTest
    fun cleanup() {
        tempFile.delete()
        File(tempFile.parentFile, tempFile.name + ".tmp").delete()
    }

    @Test
    fun `set persists then a fresh store reads it back`() {
        DesktopLastOpenChatStore(file = tempFile).set("~sampel", "~friend")
        val reloaded = DesktopLastOpenChatStore(file = tempFile)
        assertEquals("~friend", reloaded.state.value["~sampel"])
    }

    @Test
    fun `clear persists removal`() {
        val s1 = DesktopLastOpenChatStore(file = tempFile)
        s1.set("~sampel", "~friend")
        s1.clear("~sampel")
        val s2 = DesktopLastOpenChatStore(file = tempFile)
        assertNull(s2.state.value["~sampel"])
    }

    @Test
    fun `missing file means empty state - no exception`() {
        // tempFile was deleted in init — store should construct cleanly.
        val s = DesktopLastOpenChatStore(file = tempFile)
        assertTrue(s.state.value.isEmpty())
    }

    @Test
    fun `corrupt file means empty state - no exception`() {
        tempFile.writeText("{not valid json")
        val s = DesktopLastOpenChatStore(file = tempFile)
        assertTrue(s.state.value.isEmpty())
    }

    @Test
    fun `multiple ships round-trip independently`() {
        val s = DesktopLastOpenChatStore(file = tempFile)
        s.set("~a", "~x")
        s.set("~b", "~y")
        val reloaded = DesktopLastOpenChatStore(file = tempFile)
        assertEquals("~x", reloaded.state.value["~a"])
        assertEquals("~y", reloaded.state.value["~b"])
    }
}
