package io.nisfeb.talon.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import io.nisfeb.talon.ui.theme.TalonTheme
import io.nisfeb.talon.urbit.StoryPart
import io.nisfeb.talon.urbit.URL_TAG
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** "right clicking on a link should have the option to copy the link". */
@OptIn(ExperimentalTestApi::class)
class StoryLinkMenuTest {
    private val copied = mutableListOf<String>()

    private fun story(block: androidx.compose.ui.test.ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            CompositionLocalProvider(
                LocalClipboardManager provides object : ClipboardManager {
                    override fun getText(): AnnotatedString? = null
                    override fun setText(annotatedString: AnnotatedString) { copied += annotatedString.text }
                },
            ) {
                TalonTheme(darkTheme = false) {
                    StoryRenderer(parts = listOf(StoryPart.Text(buildAnnotatedString {
                        append("plain words first, then ")
                        pushStringAnnotation(URL_TAG, "https://x.test/report")
                        append("the report")
                        pop()
                    })))
                }
            }
        }
        waitForIdle()
        block()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.shows(t: String) =
        onAllNodesWithText(t).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `right-clicking a link offers its address to copy`() = story {
        onNodeWithText("plain words first, then the report").performMouseInput { rightClick(centerRight - Offset(20f, 0f)) }
        waitForIdle()
        onNodeWithText("Copy link").performMouseInput { click(center) }
        waitForIdle()
        assertEquals(listOf("https://x.test/report"), copied)
    }

    @Test
    fun `right-clicking plain words offers no link`() = story {
        onNodeWithText("plain words first, then the report").performMouseInput { rightClick(centerLeft + Offset(20f, 0f)) }
        waitForIdle()
        assertTrue(!shows("Copy link"))
    }
}
