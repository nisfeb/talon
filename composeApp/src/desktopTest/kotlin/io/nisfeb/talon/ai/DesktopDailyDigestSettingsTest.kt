package io.nisfeb.talon.ai

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioral tests for the JSON-file-backed digest settings store.
 *
 * Critical guarantees the production app/ Android impl gives, that
 * we want this desktop port to match:
 *   1. Missing file → default state (enabled=false, 06:00).
 *   2. Persist → reload sees the same values across two
 *      instances pointed at the same file.
 *   3. Corrupt JSON in the file doesn't crash; we fall back to
 *      defaults.
 *   4. Atomic writes leave no `.tmp` leftover after persist.
 *   5. setEnabled / setTime fire onChange with the right Change kind.
 *   6. applyRemote bypasses onChange (avoids ping-ponging settings
 *      back to the ship in the bootstrap path).
 */
class DesktopDailyDigestSettingsTest {
    private lateinit var tmpDir: File
    private lateinit var file: File

    @Before
    fun setUp() {
        tmpDir = createTempDirectory(prefix = "talon-digest-test-").toFile()
        file = File(tmpDir, "daily_digest.json")
    }

    @After
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun `default state when file does not exist`() {
        val store = DesktopDailyDigestSettings(file)
        val state = store.state.value
        assertFalse(state.enabled)
        assertEquals(6, state.hourOfDay)
        assertEquals(0, state.minuteOfDay)
        assertFalse(file.exists(), "default state must not write a file")
    }

    @Test
    fun `setEnabled persists and survives reload`() {
        DesktopDailyDigestSettings(file).setEnabled(true)
        assertTrue(file.exists())

        val reloaded = DesktopDailyDigestSettings(file)
        assertTrue(reloaded.state.value.enabled)
    }

    @Test
    fun `setTime persists and survives reload`() {
        DesktopDailyDigestSettings(file).setTime(hourOfDay = 21, minuteOfDay = 30)

        val reloaded = DesktopDailyDigestSettings(file)
        val state = reloaded.state.value
        assertEquals(21, state.hourOfDay)
        assertEquals(30, state.minuteOfDay)
    }

    @Test
    fun `corrupt file falls back to defaults instead of crashing`() {
        file.writeText("{ this is not valid json")

        val store = DesktopDailyDigestSettings(file)
        val state = store.state.value
        assertFalse(state.enabled)
        assertEquals(6, state.hourOfDay)
        assertEquals(0, state.minuteOfDay)
    }

    @Test
    fun `atomic move leaves no tmp file after persist`() {
        DesktopDailyDigestSettings(file).setEnabled(true)
        val tmp = File(tmpDir, "daily_digest.json.tmp")
        assertFalse(tmp.exists(), "atomic move should not leave .tmp behind")
    }

    @Test
    fun `setEnabled fires onChange with Toggled and pushBack=false`() {
        val store = DesktopDailyDigestSettings(file)
        val changes = mutableListOf<Pair<DailyDigestSettings.Change, Boolean>>()
        store.onChange = { kind, pushBack -> changes.add(kind to pushBack) }
        store.setEnabled(true)
        assertEquals(1, changes.size)
        assertEquals(DailyDigestSettings.Change.Toggled, changes[0].first)
        assertFalse(changes[0].second)
    }

    @Test
    fun `setEnabled with same value does not fire onChange or rewrite file`() {
        val store = DesktopDailyDigestSettings(file)
        var fired = 0
        store.onChange = { _, _ -> fired++ }
        store.setEnabled(false)  // already false
        assertEquals(0, fired)
        assertFalse(file.exists(), "no-op setEnabled must not touch disk")
    }

    @Test
    fun `setTime fires onChange with TimeChanged`() {
        val store = DesktopDailyDigestSettings(file)
        val changes = mutableListOf<DailyDigestSettings.Change>()
        store.onChange = { kind, _ -> changes.add(kind) }
        store.setTime(hourOfDay = 9, minuteOfDay = 15)
        assertEquals(1, changes.size)
        assertEquals(DailyDigestSettings.Change.TimeChanged, changes[0])
    }

    @Test
    fun `applyRemote persists state but does NOT fire onChange`() {
        val store = DesktopDailyDigestSettings(file)
        var fired = 0
        store.onChange = { _, _ -> fired++ }
        store.applyRemote(enabled = true, hourOfDay = 18, minuteOfDay = 45)

        assertEquals(0, fired, "applyRemote must bypass onChange to avoid pingpong")

        // But the new state IS persisted:
        val reloaded = DesktopDailyDigestSettings(file)
        assertTrue(reloaded.state.value.enabled)
        assertEquals(18, reloaded.state.value.hourOfDay)
        assertEquals(45, reloaded.state.value.minuteOfDay)
    }

    @Test
    fun `setTime rejects out-of-range values`() {
        val store = DesktopDailyDigestSettings(file)
        assertNotNull(runCatching { store.setTime(24, 0) }.exceptionOrNull())
        assertNotNull(runCatching { store.setTime(0, 60) }.exceptionOrNull())
        assertNotNull(runCatching { store.setTime(-1, 0) }.exceptionOrNull())
    }

    @Test
    fun `emitSyncToggledOff fires SyncToggledOff with pushBack=true`() {
        val store = DesktopDailyDigestSettings(file)
        var captured: Pair<DailyDigestSettings.Change, Boolean>? = null
        store.onChange = { kind, pushBack -> captured = kind to pushBack }
        store.emitSyncToggledOff()
        assertEquals(DailyDigestSettings.Change.SyncToggledOff, captured?.first)
        assertEquals(true, captured?.second)
    }

    @Test
    fun `emitSyncToggledOff is no-op when onChange is null`() {
        val store = DesktopDailyDigestSettings(file)
        // Don't set onChange. Should not throw.
        store.emitSyncToggledOff()
        assertNull(store.onChange)
    }
}
