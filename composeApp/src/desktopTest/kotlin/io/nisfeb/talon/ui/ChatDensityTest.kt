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
    fun `the setting reaches the rows and scales every sp, on top of the user's own scale`() = runComposeUiTest {
        val settings = InMemoryUiSettings()
        var seen: Pair<ChatDensity, Float>? = null
        setContent {
            CompositionLocalProvider(*chatDensityLocals(settings)) {
                seen = LocalChatDensity.current to LocalDensity.current.fontScale
                Text("x")
            }
        }
        val base = seen!!.second
        assertEquals(ChatDensity.Comfortable, seen!!.first)

        settings.setDensity(Density.Compact)
        waitForIdle()
        assertEquals(ChatDensity.Compact, seen!!.first)
        assertEquals(base * 0.90f, seen!!.second, 0.0001f)

        settings.setFontScale(1.5f)
        waitForIdle()
        assertEquals(base * 0.90f * 1.5f, seen!!.second, 0.0001f)
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
