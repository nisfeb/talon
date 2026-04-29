package io.nisfeb.talon.ui

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopUiSettingsTest {
    private lateinit var tmpDir: File
    private lateinit var file: File

    @Before
    fun setUp() {
        tmpDir = createTempDirectory(prefix = "talon-ui-test-").toFile()
        file = File(tmpDir, "ui.json")
    }

    @After
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun `default hideComposerButtons is false when file does not exist`() {
        val store = DesktopUiSettings(file)
        assertFalse(store.hideComposerButtons.value)
        assertFalse(file.exists())
    }

    @Test
    fun `setHideComposerButtons true persists and survives reload`() {
        DesktopUiSettings(file).setHideComposerButtons(true)
        assertTrue(file.exists())

        val reloaded = DesktopUiSettings(file)
        assertTrue(reloaded.hideComposerButtons.value)
    }

    @Test
    fun `setHideComposerButtons toggle flips both directions`() {
        val store = DesktopUiSettings(file)
        store.setHideComposerButtons(true)
        assertTrue(store.hideComposerButtons.value)
        store.setHideComposerButtons(false)
        assertFalse(store.hideComposerButtons.value)
    }

    @Test
    fun `setHideComposerButtons with same value is a no-op and does not touch disk`() {
        DesktopUiSettings(file).setHideComposerButtons(false)  // already default
        assertFalse(file.exists())
    }

    @Test
    fun `corrupt JSON falls back to default`() {
        file.writeText("not json")
        val store = DesktopUiSettings(file)
        assertFalse(store.hideComposerButtons.value)
    }

    @Test
    fun `atomic move leaves no tmp file after persist`() {
        DesktopUiSettings(file).setHideComposerButtons(true)
        val tmp = File(tmpDir, "ui.json.tmp")
        assertFalse(tmp.exists())
    }
}
