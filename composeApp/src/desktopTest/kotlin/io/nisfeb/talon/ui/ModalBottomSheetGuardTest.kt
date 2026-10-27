package io.nisfeb.talon.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Talon's bottom sheet, not Material's: Material's drags under a mouse,
 * so a click that moved a few pixels was lost (TalonSheets.kt). This
 * fails the build for app code reaching past it.
 */
class ModalBottomSheetGuardTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private val material = Regex("""import androidx\.compose\.material3\.ModalBottomSheet$|androidx\.compose\.material3\.ModalBottomSheet\(""", RegexOption.MULTILINE)

    @Test
    fun `app code opens its sheets through Talon's`() {
        val bad = listOf("commonMain", "androidMain", "desktopMain", "iosMain")
            .map { File(root, "composeApp/src/$it") }
            .filter { it.exists() }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "TalonSheets.kt" }.toList() }
            .filter { material.containsMatchIn(it.readText()) }
            .map { it.relativeTo(root).path }
        assertTrue(
            bad.isEmpty(),
            "Material's draggable sheet in: $bad. Use io.nisfeb.talon.ui.ModalBottomSheet (TalonSheets.kt), which a mouse cannot drag.",
        )
    }
}
