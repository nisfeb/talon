package io.nisfeb.talon.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class CopyButtonTest {
    // Most copy buttons said nothing; people pasted to see if they had worked.
    @Test
    fun `a copy says it was copied, for a moment`() = runComposeUiTest {
        var clip = ""
        val board = object : ClipboardManager {
            override fun getText() = AnnotatedString(clip)
            override fun setText(annotatedString: AnnotatedString) { clip = annotatedString.text }
        }
        setContent { CompositionLocalProvider(LocalClipboardManager provides board) { CopyButton({ "~zod" }, "Copy ship name") } }
        onNodeWithText("Copy ship name").performClick()
        assertEquals("~zod", clip)
        onNodeWithText("Copied").assertExists()
        mainClock.advanceTimeBy(COPIED_FOR_MS + 100)
        onNodeWithText("Copy ship name").assertExists()
    }
}
