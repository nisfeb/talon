package io.nisfeb.talon.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.StoryPart
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A story shown with no link handler of its own, as a gallery or
 * notebook post is, still opens its links: every link in a collection
 * post did nothing, PDFs and web pages alike.
 */
class StoryLinkTapTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a link in a story opens without a handler passed`() = runComposeUiTest {
        val opened = mutableListOf<String>()
        val handler = object : UriHandler {
            override fun openUri(uri: String) { opened += uri }
        }
        setContent {
            TalonTheme(darkTheme = false) {
                CompositionLocalProvider(LocalUriHandler provides handler) {
                    StoryRenderer(
                        parts = listOf(StoryPart.LinkPreview("https://example.com/report.pdf", "The report", null, null, null)),
                    )
                }
            }
        }
        onNodeWithText("The report").performClick()
        waitForIdle()
        assertEquals(listOf("https://example.com/report.pdf"), opened)
    }
}
