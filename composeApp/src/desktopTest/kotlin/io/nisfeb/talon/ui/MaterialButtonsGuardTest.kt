package io.nisfeb.talon.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Talon's buttons, not Material's. Material draws a button as a pill and
 * takes no shape for it from the theme, so every one of them was the one
 * round thing beside chips, fields and cards drawn at 6 to 12 (see
 * TalonButtons.kt). This fails the build for app code reaching past them.
 */
class MaterialButtonsGuardTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private val material = Regex("""import androidx\.compose\.material3\.(Button|OutlinedButton|TextButton)$|androidx\.compose\.material3\.(Button|OutlinedButton|TextButton)\(""", RegexOption.MULTILINE)

    @Test
    fun `app code draws its buttons with Talon's corners`() {
        val bad = listOf("commonMain", "androidMain", "desktopMain", "iosMain")
            .map { File(root, "composeApp/src/$it") }
            .filter { it.exists() }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "TalonButtons.kt" }.toList() }
            .filter { material.containsMatchIn(it.readText()) }
            .map { it.relativeTo(root).path }
        assertTrue(
            bad.isEmpty(),
            "Material's pill buttons in: $bad. Use io.nisfeb.talon.ui.Button, OutlinedButton or TextButton " +
                "(TalonButtons.kt), which take Talon's corners.",
        )
    }
}
