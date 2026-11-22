package io.nisfeb.talon.urbit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Android's application builds a settings sync for every ship it moves
 * to and keeps one settings store. Each sync must be given the store and
 * the one before it must let go: only the first was, and a new phone's
 * first session took none of the ship's theme or orders (2026-11-21).
 * Read from the source, as SectionFlagsGuardTest reads the shells: no
 * test here can run the Android application.
 */
class AndroidSyncWiringGuardTest {
    @Test
    fun `every sync Android builds is given the settings store, and the last lets go`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val src = File(root, "composeApp/src/androidMain/kotlin/io/nisfeb/talon/TalonApplication.kt").readText()
        val build = src.substringAfter("private fun buildShipScoped(").substringBefore("\n    fun ")
        assertTrue("SettingsSyncImpl(" in build, "the sync is built per ship here")
        val made = build.indexOf("settingsSync = io.nisfeb.talon.urbit.SettingsSyncImpl(")
        assertTrue(build.indexOf("settingsSync.detachUiSettings()") in 0 until made, "the sync before lets go of the store first")
        assertTrue(build.indexOf("settingsSync.attachUiSettings(uiSettings", made) > made, "the new sync is given the store")
    }
}
