package io.nisfeb.talon.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * iOS's back swipe failed about half the time (2026-10-28): Talon's own
 * edge strip lost the outer edge to Compose's native recognizer, which
 * had no handler, and a swipe past the strip opened the sections drawer.
 * Back is now Compose's navigation events on iOS, and the drawer swipes
 * only at the top of the back stack. Neither gesture runs on desktop, so
 * the wiring is guarded by its source and the count by a run.
 */
@OptIn(ExperimentalTestApi::class)
class BackStackGuardTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }
    private fun read(path: String) = File(root, path).readText()

    @Test
    fun `each enabled handler counts while it is on, and none once gone`() = runComposeUiTest {
        var a by mutableStateOf(true)
        var b by mutableStateOf(false)
        var shown by mutableStateOf(true)
        val base = BackDepth.depth
        setContent {
            if (shown) {
                BackDepth.Count(a)
                BackDepth.Count(b)
            }
        }
        waitForIdle()
        assertEquals(base + 1, BackDepth.depth)
        b = true
        waitForIdle()
        assertEquals(base + 2, BackDepth.depth)
        a = false
        waitForIdle()
        assertEquals(base + 1, BackDepth.depth)
        shown = false
        waitForIdle()
        assertEquals(base, BackDepth.depth, "a screen that leaves takes its handlers' count with it")
    }

    @Test
    fun `iOS back is Compose's own edge swipe, and the strip is gone`() {
        val actual = read("composeApp/src/iosMain/kotlin/io/nisfeb/talon/ui/PlatformBackHandler.ios.kt")
        assertTrue("NavigationBackHandler(" in actual && "isBackEnabled = enabled" in actual && "onBackCompleted = onBack" in actual, actual)
        assertTrue("BackDepth.Count(enabled)" in actual)
        val root = read("composeApp/src/iosMain/kotlin/io/nisfeb/talon/MainViewController.kt")
        assertFalse("detectHorizontalDragGestures" in root || "EDGE_SWIPE" in root, "a strip of our own takes touches from the native recognizer")
    }

    @Test
    fun `every Android back handler is counted`() {
        val android = read("composeApp/src/androidMain/kotlin/io/nisfeb/talon/ui/PlatformBackHandler.android.kt")
        assertTrue("BackHandler(enabled = enabled, onBack = onBack)" in android && "BackDepth.Count(enabled)" in android)
        // A handler past PlatformBackHandler is not counted, and the drawer would swipe open over it.
        val app = read("composeApp/src/androidMain/kotlin/io/nisfeb/talon/ui/TalonApp.kt")
        assertFalse(Regex("""(?<!Platform)BackHandler\(""").containsMatchIn(app), "TalonApp calls androidx's BackHandler directly")
    }

    @Test
    fun `an open drawer is shut by back, not the screen under it`() {
        val drawer = read("composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/TalonDrawer.kt")
        assertTrue("if (state.isOpen) PlatformBackHandler { scope.launch { state.close() } }" in drawer)
        assertTrue("gesturesEnabled = drawerSwipes(state.isOpen, BackDepth.depth)" in drawer)
        val app = read("composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt")
        assertTrue("if (drawerState.isOpen) io.nisfeb.talon.ui.PlatformBackHandler { drawerScope.launch { drawerState.close() } }" in app)
    }
}
