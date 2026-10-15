package io.nisfeb.talon.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ReactionPickerContentTest {

    @Test
    fun `it shows the frequent row, a category header, and picks a shortcode`() = runComposeUiTest {
        var picked: String? = null
        setContent {
            ReactionPickerContent(frequent = listOf(":fire:"), onPick = { picked = it })
        }
        // The browse grid renders category headers, not just a flat row.
        onNodeWithText("Frequently used").assertExists()
        onNodeWithText("Smileys").assertExists()
        // Tapping an emoji reports its shortcode, not its glyph.
        onAllNodesWithText("🔥").onFirst().performClick()
        assertEquals(":fire:", picked)
    }
}
