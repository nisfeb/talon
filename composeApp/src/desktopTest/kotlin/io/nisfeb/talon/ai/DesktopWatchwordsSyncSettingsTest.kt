package io.nisfeb.talon.ai

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopWatchwordsSyncSettingsTest {
    private lateinit var tmpDir: File
    private lateinit var file: File

    @Before
    fun setUp() {
        tmpDir = createTempDirectory(prefix = "talon-watch-test-").toFile()
        file = File(tmpDir, "watchwords_sync.json")
    }

    @After
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun `default false when file does not exist`() {
        val store = DesktopWatchwordsSyncSettings(file)
        assertFalse(store.enabled.value)
        assertFalse(file.exists(), "default state must not write a file")
    }

    @Test
    fun `setEnabled persists and survives reload`() {
        DesktopWatchwordsSyncSettings(file).setEnabled(true)
        assertTrue(file.exists())

        val reloaded = DesktopWatchwordsSyncSettings(file)
        assertTrue(reloaded.enabled.value)
    }

    @Test
    fun `setEnabled with same value is a no-op and does not touch disk`() {
        // Default is false. Setting to false again should not write.
        DesktopWatchwordsSyncSettings(file).setEnabled(false)
        assertFalse(file.exists())
    }

    @Test
    fun `setEnabled toggle flips state both directions`() {
        val store = DesktopWatchwordsSyncSettings(file)
        store.setEnabled(true)
        assertTrue(store.enabled.value)
        store.setEnabled(false)
        assertFalse(store.enabled.value)
    }

    @Test
    fun `corrupt file falls back to false`() {
        file.writeText("{this is not valid json}")
        val store = DesktopWatchwordsSyncSettings(file)
        assertFalse(store.enabled.value)
    }

    @Test
    fun `atomic move leaves no tmp file after persist`() {
        DesktopWatchwordsSyncSettings(file).setEnabled(true)
        val tmp = File(tmpDir, "watchwords_sync.json.tmp")
        assertFalse(tmp.exists())
    }

    @Test
    fun `unknown extra fields in the JSON are tolerated on load`() {
        // Forward-compat: a future Talon writing extra keys should
        // not break an older Talon's load path. ignoreUnknownKeys=true
        // in the JSON config covers this — verify the contract.
        file.writeText("""{"enabled": true, "futureFlag": "yes"}""")
        val store = DesktopWatchwordsSyncSettings(file)
        assertEquals(true, store.enabled.value)
    }
}
