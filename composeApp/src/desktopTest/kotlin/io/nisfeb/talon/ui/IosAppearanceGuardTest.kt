package io.nisfeb.talon.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * iOS's own sheets follow the app's light or dark, not the phone's
 * (2026-10-18: "the photo picker doesn't respect dark mode and flash
 * bangs them at night"). iOS cannot run here, so the wiring is read.
 */
class IosAppearanceGuardTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }
    private fun read(path: String) = File(root, "composeApp/src/iosMain/kotlin/io/nisfeb/talon/$path").readText()

    @Test
    fun `the theme says light or dark and every sheet takes it`() {
        assertTrue("SideEffect { IosAppearance.dark = darkTheme }" in read("ui/theme/SystemBars.ios.kt"), "the theme records it")
        val pickers = read("util/ImagePicker.ios.kt")
        assertTrue(Regex("""picker\.followAppTheme\(\)\s+(//.*\s+)?root!!\.presentViewController\(picker""").containsMatchIn(pickers), "the one present() every picker goes through")
        assertTrue("QrScanController(prompt, onCode).apply { followAppTheme() }" in read("ui/QrLoginScanner.ios.kt"), "the QR scanner")
        assertTrue("alert.followAppTheme()" in read("ui/VoiceMessages.ios.kt"), "the voice message alert")
    }
}
