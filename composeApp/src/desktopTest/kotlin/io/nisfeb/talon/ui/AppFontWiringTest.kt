package io.nisfeb.talon.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import io.nisfeb.talon.ui.theme.TalonTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The chosen font reaches the text, in both shells. */
@OptIn(ExperimentalTestApi::class)
class AppFontWiringTest {
    @Test
    fun `the theme sets every style in the chosen font, and plain text with it`() = runComposeUiTest {
        var seen: List<FontFamily?> = emptyList()
        setContent {
            TalonTheme(darkTheme = false, fontFamily = FontFamily.Serif) {
                val t = MaterialTheme.typography
                seen = listOf(t.displayLarge, t.titleMedium, t.bodyLarge, t.bodySmall, t.labelSmall, LocalTextStyle.current).map { it.fontFamily }
            }
        }
        waitForIdle()
        assertTrue(seen.isNotEmpty() && seen.all { it == FontFamily.Serif }, seen.toString())
    }

    // Nothing runs the Android shell in a test, so this is what stops either
    // shell dropping the font: Android sets its theme in MainActivity.
    @Test
    fun `both shells set the chosen font and keep the fonts fetched`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        fun has(path: String, what: String) = assertTrue(what in File(root, path).readText(), "$path does not call $what")
        has("composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt", "rememberAppFontFamily(")
        has("composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt", "rememberFontRepo(")
        has("composeApp/src/androidMain/kotlin/io/nisfeb/talon/MainActivity.kt", "rememberAppFontFamily(")
        has("composeApp/src/androidMain/kotlin/io/nisfeb/talon/ui/TalonApp.kt", "rememberFontRepo(")
    }
}
