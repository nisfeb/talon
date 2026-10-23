package io.nisfeb.talon.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "compressed, comfortable, etc settings do nothing": the Android shell
 * provided neither the density nor its font scale, so every screen there
 * read the defaults. Both shells now take them from [chatDensityLocals].
 */
@OptIn(ExperimentalTestApi::class)
class ChatDensityTest {
    @Test
    fun `the setting reaches the rows and scales every sp, and the user's own scale zooms the window`() = runComposeUiTest {
        val settings = InMemoryUiSettings()
        var seen: Pair<ChatDensity, Float>? = null
        var density = 0f
        setContent {
            CompositionLocalProvider(*chatDensityLocals(settings)) {
                seen = LocalChatDensity.current to LocalDensity.current.fontScale
                density = LocalDensity.current.density
                Text("x")
            }
        }
        val base = seen!!.second
        val baseDensity = density
        assertEquals(ChatDensity.Comfortable, seen!!.first)

        settings.setDensity(Density.Compact)
        waitForIdle()
        assertEquals(ChatDensity.Compact, seen!!.first)
        assertEquals(base * 0.90f, seen!!.second, 0.0001f)

        // Desktop: the user's scale is zoom. Icons and boxes grow with the
        // text, and the text is not scaled a second time.
        settings.setFontScale(1.5f)
        waitForIdle()
        assertEquals(baseDensity * 1.5f, density, 0.0001f)
        assertEquals(base * 0.90f, seen!!.second, 0.0001f)
    }

    // Nothing runs the Android shell in a test, so this is what stops it
    // dropping the setting again.
    @Test
    fun `both shells provide it`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        for (shell in listOf(
            "composeApp/src/androidMain/kotlin/io/nisfeb/talon/ui/TalonApp.kt",
            "composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt",
        )) {
            assertTrue("chatDensityLocals(" in File(root, shell).readText(), "$shell does not provide the density setting")
        }
    }
}
