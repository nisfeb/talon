package io.nisfeb.talon.ui

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
}
