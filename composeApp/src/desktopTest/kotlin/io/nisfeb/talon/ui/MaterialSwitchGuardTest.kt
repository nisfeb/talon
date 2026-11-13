package io.nisfeb.talon.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Groundwire's switch, not Material's: Material's tall outlined track read
 * heavy in a list of settings (review, 2026-11-05). This fails the build
 * for app code reaching past io.nisfeb.talon.ui.Switch (TalonSwitch.kt).
 */
class MaterialSwitchGuardTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private val material = Regex("""import androidx\.compose\.material3\.Switch$|androidx\.compose\.material3\.Switch\(""", RegexOption.MULTILINE)

    @Test
    fun `app code draws its switches with Groundwire's switch`() {
        val bad = listOf("commonMain", "androidMain", "desktopMain", "iosMain")
            .map { File(root, "composeApp/src/$it") }
            .filter { it.exists() }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "TalonSwitch.kt" }.toList() }
            .filter { material.containsMatchIn(it.readText()) }
            .map { it.relativeTo(root).path }
        assertTrue(bad.isEmpty(), "Material's switch in: $bad. Use io.nisfeb.talon.ui.Switch (TalonSwitch.kt).")
    }
}
