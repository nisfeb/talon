package io.nisfeb.talon.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.ui.test.performMultiModalInput
import androidx.compose.ui.test.performMouseInput
import kotlin.math.abs
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import io.nisfeb.talon.ui.screens.ImageViewerScreen
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pictures full-screen: stepped through by button or arrow key, saved, and closed. */
@OptIn(ExperimentalTestApi::class)
class ImageViewerScreenTest {
    private val did: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    private val urls = listOf("https://x.test/1.png", "https://x.test/2.png", "https://x.test/3.png")

    private fun viewer(
        urls: List<String> = this.urls,
        start: Int = 0,
        downloader: ImageDownloader = NoopImageDownloader,
        block: ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalImageDownloader provides downloader) {
                TalonTheme(darkTheme = false) { ImageViewerScreen(urls, onClose = { did += "closed" }, initialIndex = start) }
            }
        }
        block()
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the buttons step through, and stop at each end`() = viewer {
        waitUntil(timeoutMillis = 5_000) { shows("1 / 3") }
        onNodeWithContentDescription("Previous image").assertIsNotEnabled()
        onNodeWithContentDescription("Next image").performClick()
        onNodeWithContentDescription("Next image").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("3 / 3") }
        onNodeWithContentDescription("Next image").assertIsNotEnabled()
        onNodeWithContentDescription("Previous image").performClick()
        waitUntil(timeoutMillis = 5_000) { shows("2 / 3") }
    }

    @Test
    fun `arrow keys step, and Escape closes`() = viewer(start = 1) {
        waitUntil(timeoutMillis = 5_000) { shows("2 / 3") }
        onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.DirectionRight) }
        waitUntil(timeoutMillis = 5_000) { shows("3 / 3") }
        onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.DirectionLeft); pressKey(Key.DirectionLeft) }
        waitUntil(timeoutMillis = 5_000) { shows("1 / 3") }
        onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.Escape) }
        waitUntil(timeoutMillis = 5_000) { did == listOf("closed") }
    }

    @Test
    fun `one picture has no stepping, and Close closes`() = viewer(urls = urls.take(1)) {
        assertTrue(onAllNodesWithContentDescription("Next image").fetchSemanticsNodes().isEmpty())
        assertTrue(!shows("1 / 1"))
        onNodeWithContentDescription("Close").performClick()
        assertEquals(listOf("closed"), did)
    }

    @Test
    fun `saving saves the picture shown and says where`() = viewer(start = 2, downloader = object : ImageDownloader {
        override suspend fun saveImage(url: String): SaveResult { did += "save $url"; return SaveResult.Saved("Pictures/Talon") }
    }) {
        onNodeWithContentDescription("Download").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Pictures/Talon", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("save https://x.test/3.png"), did)
    }

    @Test
    fun `where pictures cannot be saved there is no save button`() = viewer {
        assertTrue(onAllNodesWithContentDescription("Download").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `nothing to show closes at once`() = viewer(urls = emptyList()) {
        waitUntil(timeoutMillis = 5_000) { did == listOf("closed") }
    }

    // Zoom and pan, by touch (2026-10-09: "unusable" on a phone). The
    // pictures do not load here, so the picture is the whole viewer.
    private fun ComposeUiTest.picture() = onNodeWithTag("viewer-image", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun ComposeUiTest.touch(block: TouchInjectionScope.() -> Unit) = onAllNodes(isRoot()).onFirst().performTouchInput(block)
    private fun near(expected: Float, actual: Float, what: String) = assertTrue(abs(expected - actual) < 2f, "$what: expected $expected, was $actual")

    @Test
    fun `a double tap zooms in about the tapped point, and a drag carries the picture with the finger`() = viewer(urls = urls.take(1)) {
        val whole = picture()
        touch { doubleClick(Offset(200f, 150f)) }
        waitForIdle()
        near(whole.width * 2.5f, picture().width, "zoomed in 2.5x")
        near(200f - 200f * 2.5f, picture().left, "the tapped point stays put")
        near(150f - 150f * 2.5f, picture().top, "the tapped point stays put")

        touch { swipe(Offset(500f, 400f), Offset(400f, 350f), durationMillis = 300) }
        waitForIdle()
        near(-400f, picture().left, "moved as far as the finger")
        near(-275f, picture().top, "moved as far as the finger")

        touch { swipe(Offset(100f, 400f), Offset(900f, 400f), durationMillis = 300) }
        waitForIdle()
        near(0f, picture().left, "a flick stops at the picture's edge")

        touch { doubleClick(Offset(300f, 300f)) }
        waitForIdle()
        assertEquals(whole, picture(), "back where it started")
    }

    @Test
    fun `a pinch zooms about the fingers`() = viewer(urls = urls.take(1)) {
        val whole = picture()
        touch { pinch(Offset(250f, 300f), Offset(150f, 300f), Offset(350f, 300f), Offset(450f, 300f), durationMillis = 400) }
        waitForIdle()
        near(whole.width * 3f, picture().width, "zoomed 3x")
        near(300f - 300f * 3f, picture().left, "the point between the fingers stays put")
        near(300f - 300f * 3f, picture().top, "the point between the fingers stays put")
    }

    @Test
    fun `a swipe steps to the next picture, but not once zoomed`() = viewer {
        waitUntil(timeoutMillis = 5_000) { shows("1 / 3") }
        touch { swipeLeft(startX = 800f, endX = 200f, durationMillis = 300) }
        waitUntil(timeoutMillis = 5_000) { shows("2 / 3") }
        touch { doubleClick(Offset(400f, 300f)) }
        waitForIdle()
        touch { swipeLeft(startX = 800f, endX = 200f, durationMillis = 300) }
        waitForIdle()
        assertTrue(shows("2 / 3"), "a drag on a zoomed picture pans it")
    }

    // Desktop: the zoom 1.8.15 lost with Compose's transformable (2026-10-10 review).
    @Test
    fun `ctrl and the wheel zoom about the pointer, and the wheel alone pans only when zoomed`() = viewer(urls = urls.take(1)) {
        val whole = picture()
        onAllNodes(isRoot()).onFirst().performMouseInput { moveTo(Offset(300f, 200f)); scroll(3f) }
        waitForIdle()
        assertEquals(whole, picture(), "a plain wheel at 1x does nothing")
        onAllNodes(isRoot()).onFirst().performMultiModalInput {
            key { keyDown(Key.CtrlLeft) }
            mouse { moveTo(Offset(300f, 200f)); scroll(-5f) }
            key { keyUp(Key.CtrlLeft) }
        }
        waitForIdle()
        val zoom = picture().width / whole.width
        assertTrue(zoom > 2f, "zoomed in, was ${zoom}x")
        near(300f - 300f * zoom, picture().left, "the point under the pointer stays put")
        near(200f - 200f * zoom, picture().top, "the point under the pointer stays put")
        val top = picture().top
        onAllNodes(isRoot()).onFirst().performMouseInput { scroll(2f) }
        waitForIdle()
        assertTrue(picture().top < top, "the wheel pans down the zoomed picture: ${picture().top} vs $top")
    }

    @Test
    fun `a zoomed picture is put back in bounds when the window shrinks`() = runComposeUiTest {
        var w by androidx.compose.runtime.mutableStateOf(800)
        var h by androidx.compose.runtime.mutableStateOf(600)
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f)) {
                TalonTheme(darkTheme = false) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(w.dp, h.dp)) {
                        ImageViewerScreen(urls.take(1), onClose = {})
                    }
                }
            }
        }
        waitForIdle()
        onAllNodes(isRoot()).onFirst().performTouchInput { doubleClick(Offset(790f, 590f)) }
        waitForIdle()
        near(790f - 790f * 2.5f, picture().left, "zoomed about the bottom right")
        w = 400; h = 300
        waitForIdle()
        near(400f - 400f * 2.5f, picture().left, "pulled back so the picture still covers the window")
        near(300f - 300f * 2.5f, picture().top, "and from above")
    }
}
