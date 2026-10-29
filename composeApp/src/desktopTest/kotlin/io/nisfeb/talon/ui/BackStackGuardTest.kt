package io.nisfeb.talon.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * iOS's back swipe failed about half the time (2026-10-28): Talon's own
 * edge strip lost the outer edge to Compose's native recognizer, which
 * had no handler, and a swipe past the strip opened the sections drawer.
 * Back is now Compose's navigation events on iOS, and the drawer swipes
 * only at the top of the back stack. Neither gesture runs on desktop, so
 * the wiring is guarded by its source (BackDepthTest runs the count).
 */
class BackStackGuardTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }
    private fun read(path: String) = File(root, path).readText()

    @Test
    fun `iOS back is Compose's own edge swipe, and the strip is gone`() {
        val actual = read("composeApp/src/iosMain/kotlin/io/nisfeb/talon/ui/PlatformBackHandler.ios.kt")
        assertTrue("NavigationBackHandler(" in actual && "isBackEnabled = enabled" in actual, actual)
        val completed = actual.substringAfter("onBackCompleted = {").substringBefore("},")
        assertTrue("IosBackSwipe.done()" in completed && "onBack()" in completed, completed)
        assertTrue("onBackCancelled = { IosBackSwipe.settle() }" in actual, "a swipe let go short of the mark springs back")
        assertTrue("BackDepth.Count(enabled)" in actual)
        assertTrue("IosBackSwipe.follow(swipe.latestEvent.progress)" in actual, "the screen follows its own handler's swipe")
        val root = read("composeApp/src/iosMain/kotlin/io/nisfeb/talon/MainViewController.kt")
        assertFalse("detectHorizontalDragGestures" in root || "EDGE_SWIPE" in root, "a strip of our own takes touches from the native recognizer")
        assertTrue("IosBackSwipeHost(themePreference)" in root)
    }

    @Test
    fun `iOS's swipe from anywhere leaves the edge to iOS and the top to the drawer`() {
        val host = read("composeApp/src/iosMain/kotlin/io/nisfeb/talon/ui/IosBackSwipe.kt")
        // One swipe never starts two backs: the edge band is the native recognizer's.
        assertTrue("down.position.x < EDGE_BAND.toPx()" in host && "private val EDGE_BAND = 32.dp" in host)
        assertTrue("BackDepth.depth == 0" in host, "at the top the swipe is the drawer's")
        assertTrue("DirectNavigationEventInput()" in host && "dispatcher?.addInput(input)" in host, "it goes through the same dispatcher as iOS's own")
        // Read the finger before the layer moves the screen, or the screen chases its own offset.
        val reads = host.indexOf(".swipeBackAnywhere(input, width)")
        assertTrue(reads >= 0 && reads < host.indexOf(".graphicsLayer { translationX"))
        assertTrue("backSwipeCommits(" in host)
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
