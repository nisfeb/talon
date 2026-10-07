package io.nisfeb.talon.ui

import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SubmitOnEnterTest {
    // A ship's name in a multi-line field took Enter as a new line no name has.
    @Test
    fun `Enter submits a ship's name, Shift+Enter breaks the line, and nothing goes while it cannot`() = runComposeUiTest {
        var sent = 0
        var ready by mutableStateOf(false)
        setContent {
            var text by remember { mutableStateOf("") }
            OutlinedTextField(text, { text = it }, singleLine = false, modifier = Modifier.submitOnEnter(ready) { sent++ })
        }
        val field = onNode(hasSetTextAction())
        field.performTextInput("~zod")
        field.performKeyInput { pressKey(Key.Enter) }
        assertEquals(0, sent, "not while the button would not")
        ready = true
        waitForIdle()
        field.performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, sent)
        field.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } }
        assertEquals(1, sent, "Shift+Enter is a line")
    }
}
