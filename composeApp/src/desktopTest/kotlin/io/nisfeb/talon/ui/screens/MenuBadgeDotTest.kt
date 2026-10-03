package io.nisfeb.talon.ui.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class MenuBadgeDotTest {
    // A dot is color alone; a screen reader said nothing of it.
    @Test
    fun `the unread dot is announced as unread`() = runComposeUiTest {
        setContent { MenuBadgeDot() }
        onNodeWithContentDescription("Unread").assertExists()
    }
}
