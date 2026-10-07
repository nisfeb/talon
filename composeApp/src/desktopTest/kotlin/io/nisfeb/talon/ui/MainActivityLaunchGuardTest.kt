package io.nisfeb.talon.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * One MainActivity, alive across Back. As standard, a shortcut, the call
 * screen or a plain start stacked another copy, each with its own calls
 * loop, beacon and pollers (eight at once on one phone); and Back
 * finished it, so each reopen read the ship from the start again.
 * Device wiring that no unit test reaches: guarded by reading the source.
 */
class MainActivityLaunchGuardTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    @Test
    fun `MainActivity is single task`() {
        val manifest = File(root, "composeApp/src/androidMain/AndroidManifest.xml").readText()
        val main = Regex("""<activity[^>]*io\.nisfeb\.talon\.MainActivity"[^>]*>""").find(manifest)?.value
            ?: error("MainActivity not declared")
        assertTrue("""android:launchMode="singleTask"""" in main, main)
    }

    @Test
    fun `Back at the root sends the task behind`() {
        val src = File(root, "composeApp/src/androidMain/kotlin/io/nisfeb/talon/MainActivity.kt").readText()
        val back = src.indexOf("onBackPressedDispatcher.addCallback(this) { moveTaskToBack(true) }")
        assertTrue(back >= 0, "the root Back callback is gone")
        assertTrue(back < src.indexOf("setContent {"), "added after setContent, it would come before every screen's BackHandler")
    }
}
