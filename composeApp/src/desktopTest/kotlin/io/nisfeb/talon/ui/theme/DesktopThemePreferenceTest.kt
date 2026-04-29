package io.nisfeb.talon.ui.theme

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DesktopThemePreferenceTest {
    private lateinit var tmpDir: File
    private lateinit var file: File

    @Before
    fun setUp() {
        tmpDir = createTempDirectory(prefix = "talon-theme-test-").toFile()
        file = File(tmpDir, "theme.json")
    }

    @After
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun `default System when file does not exist`() {
        val store = DesktopThemePreference(file)
        assertEquals(ThemePreference.Mode.System, store.mode.value)
        assertFalse(file.exists())
    }

    @Test
    fun `setMode Light persists and survives reload`() {
        DesktopThemePreference(file).setMode(ThemePreference.Mode.Light)

        val reloaded = DesktopThemePreference(file)
        assertEquals(ThemePreference.Mode.Light, reloaded.mode.value)
    }

    @Test
    fun `setMode Dark persists and survives reload`() {
        DesktopThemePreference(file).setMode(ThemePreference.Mode.Dark)

        val reloaded = DesktopThemePreference(file)
        assertEquals(ThemePreference.Mode.Dark, reloaded.mode.value)
    }

    @Test
    fun `setMode round-trips between all three modes`() {
        val store = DesktopThemePreference(file)
        store.setMode(ThemePreference.Mode.Dark)
        assertEquals(ThemePreference.Mode.Dark, store.mode.value)
        store.setMode(ThemePreference.Mode.Light)
        assertEquals(ThemePreference.Mode.Light, store.mode.value)
        store.setMode(ThemePreference.Mode.System)
        assertEquals(ThemePreference.Mode.System, store.mode.value)
    }

    @Test
    fun `setMode with same value is a no-op and does not touch disk`() {
        DesktopThemePreference(file).setMode(ThemePreference.Mode.System)  // already default
        assertFalse(file.exists())
    }

    @Test
    fun `corrupt JSON falls back to System`() {
        file.writeText("not json")
        val store = DesktopThemePreference(file)
        assertEquals(ThemePreference.Mode.System, store.mode.value)
    }

    @Test
    fun `unknown enum string falls back to System`() {
        // A future version writing Mode.AutoSepia (etc) shouldn't
        // crash an older Talon that reads the file. The current
        // load-recovery catches enum lookup failure and returns
        // System.
        file.writeText("""{"mode": "Mauve"}""")
        val store = DesktopThemePreference(file)
        assertEquals(ThemePreference.Mode.System, store.mode.value)
    }

    @Test
    fun `atomic move leaves no tmp file after persist`() {
        DesktopThemePreference(file).setMode(ThemePreference.Mode.Dark)
        val tmp = File(tmpDir, "theme.json.tmp")
        assertFalse(tmp.exists())
    }
}
