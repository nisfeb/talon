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

    // ── accent settings ──────────────────────────────────────────

    @Test
    fun `default accentSettings has enabled=null mode=Profile no hex`() {
        val accent = DesktopUiSettings(file).accentSettings.value
        kotlin.test.assertEquals(null, accent.enabled)
        kotlin.test.assertEquals(AccentMode.Profile, accent.mode)
        kotlin.test.assertEquals(null, accent.customHex)
    }

    @Test
    fun `setAccentSettings persists across reload`() {
        DesktopUiSettings(file).setAccentSettings(
            AccentSettings(enabled = true, mode = AccentMode.Custom, customHex = "#FF00AA"),
        )
        val reloaded = DesktopUiSettings(file).accentSettings.value
        kotlin.test.assertEquals(true, reloaded.enabled)
        kotlin.test.assertEquals(AccentMode.Custom, reloaded.mode)
        kotlin.test.assertEquals("#FF00AA", reloaded.customHex)
    }

    @Test
    fun `setAccentSettings explicit-off survives the null-vs-false distinction`() {
        // The user opting OUT must persist as Boolean false, not
        // collapse back to null — otherwise a single-ship → multi-
        // ship transition would silently flip the accent back on.
        DesktopUiSettings(file).setAccentSettings(AccentSettings(enabled = false))
        val reloaded = DesktopUiSettings(file).accentSettings.value
        kotlin.test.assertEquals(false, reloaded.enabled)
    }

    @Test
    fun `AccentSettings isEnabled defaults to multiShip when stored value unset`() {
        kotlin.test.assertEquals(
            true,
            AccentSettings.isEnabled(AccentSettings(enabled = null), multiShip = true),
        )
        kotlin.test.assertEquals(
            false,
            AccentSettings.isEnabled(AccentSettings(enabled = null), multiShip = false),
        )
    }

    @Test
    fun `AccentSettings isEnabled honors explicit stored value over multiShip default`() {
        // Multi-ship user who explicitly turned the accent off stays off.
        kotlin.test.assertEquals(
            false,
            AccentSettings.isEnabled(AccentSettings(enabled = false), multiShip = true),
        )
        // Single-ship user who opted in stays on even though they're alone.
        kotlin.test.assertEquals(
            true,
            AccentSettings.isEnabled(AccentSettings(enabled = true), multiShip = false),
        )
    }
}
