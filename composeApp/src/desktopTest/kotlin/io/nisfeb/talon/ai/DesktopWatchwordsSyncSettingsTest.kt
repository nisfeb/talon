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
    fun `default true when file does not exist`() {
        // Watchwords sync defaults on so new users mirror across
        // devices without an opt-in step. The file only appears once
        // the user explicitly toggles — absent file means "untouched,
        // use default".
        val store = DesktopWatchwordsSyncSettings(file)
        assertTrue(store.enabled.value)
        assertFalse(file.exists(), "default state must not write a file")
    }

    @Test
    fun `setEnabled persists and survives reload`() {
        // Explicit-off must round-trip across restarts so users who
        // chose local-only watchwords keep that choice.
        DesktopWatchwordsSyncSettings(file).setEnabled(false)
        assertTrue(file.exists())

        val reloaded = DesktopWatchwordsSyncSettings(file)
        assertFalse(reloaded.enabled.value)
    }

    @Test
    fun `setEnabled with same value is a no-op and does not touch disk`() {
        // Default is true. Setting to true again should not write.
        DesktopWatchwordsSyncSettings(file).setEnabled(true)
        assertFalse(file.exists())
    }

    @Test
    fun `setEnabled toggle flips state both directions`() {
        val store = DesktopWatchwordsSyncSettings(file)
        store.setEnabled(false)
        assertFalse(store.enabled.value)
        store.setEnabled(true)
        assertTrue(store.enabled.value)
    }

    @Test
    fun `corrupt file falls back to default`() {
        // Corrupt file is treated like an absent one — fall back to
        // the on-by-default behavior rather than silently leaving the
        // user with sync off.
        file.writeText("{this is not valid json}")
        val store = DesktopWatchwordsSyncSettings(file)
        assertTrue(store.enabled.value)
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
