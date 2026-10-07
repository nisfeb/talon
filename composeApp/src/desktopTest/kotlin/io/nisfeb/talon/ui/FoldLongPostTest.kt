package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/** A post more than ten lines long took the screen; it folds, opens, and folds back. */
@OptIn(ExperimentalTestApi::class)
class FoldLongPostTest {
    private fun post(lines: Int, hasMedia: Boolean = false, block: androidx.compose.ui.test.ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            TalonTheme(darkTheme = false) {
                Column(Modifier.width(400.dp).testTag("post")) {
                    FoldLongPost("p1", hasMedia) {
                        Text((1..lines).joinToString("\n") { "line $it" }, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        block()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.height() = onNodeWithTag("post").fetchSemanticsNode().size.height

    @Test
    fun `a long post folds, opens on Show more, and folds on Show less`() = post(lines = 20) {
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Show more").fetchSemanticsNodes().isNotEmpty() }
        val folded = height()
        onNodeWithText("Show more").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Show less").fetchSemanticsNodes().isNotEmpty() }
        val opened = height()
        assertTrue(opened > folded * 3 / 2, "opened $opened, folded $folded")
        onNodeWithText("Show less").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Show more").fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        kotlin.test.assertEquals(folded, height())
    }

    @Test
    fun `a short post, or one just over, does not fold`() {
        post(lines = 6) { waitForIdle(); assertTrue(onAllNodesWithText("Show more").fetchSemanticsNodes().isEmpty()) }
        post(lines = 11) { waitForIdle(); assertTrue(onAllNodesWithText("Show more").fetchSemanticsNodes().isEmpty()) }
    }

    @Test
    fun `a post with a picture has the picture's room before it folds`() = post(lines = 20, hasMedia = true) {
        waitForIdle()
        assertTrue(onAllNodesWithText("Show more").fetchSemanticsNodes().isEmpty())
    }
}
