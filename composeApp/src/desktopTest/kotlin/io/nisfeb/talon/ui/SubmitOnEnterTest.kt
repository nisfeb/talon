package io.nisfeb.talon.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
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

    // A comment box: one send per press, Cmd or Ctrl or the keypad's
    // Enter alike; Shift+Enter puts a line where the selection was; an
    // input method's Enter (it confirms a candidate) is left to it.
    @Test
    fun `a comment sends once per Enter, breaks a line on Shift, and leaves a composing Enter alone`() = runComposeUiTest {
        var sent = 0
        var ready by mutableStateOf(false)
        var value by mutableStateOf(TextFieldValue("abc", selection = TextRange(1, 2)))
        setContent {
            Box(Modifier.size(10.dp).sendOnEnter(value, { value = it }, ready) { sent++ }.testTag("k").focusable())
        }
        val k = onNodeWithTag("k")
        k.requestFocus()
        k.performKeyInput { pressKey(Key.Enter) }
        assertEquals(0, sent, "not while the button would not")
        assertEquals("abc", value.text, "and no line either")
        ready = true
        waitForIdle()
        k.performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, sent, "once, not on the key's release too")
        k.performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.Enter) } }
        k.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.NumPadEnter) } }
        assertEquals(3, sent)
        k.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } }
        assertEquals(3, sent)
        assertEquals("a\nc", value.text, "the line takes the selection's place")
        assertEquals(TextRange(2), value.selection)
        value = TextFieldValue("にほ", composition = TextRange(0, 2))
        waitForIdle()
        k.performKeyInput { pressKey(Key.Enter) }
        k.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } }
        assertEquals(3, sent, "the input method's Enter is its own")
        assertEquals("にほ", value.text)
    }

    // A post's form: only Cmd or Ctrl with Enter posts, once.
    @Test
    fun `a form posts on Cmd or Ctrl with Enter only, once, and not while it cannot`() = runComposeUiTest {
        var sent = 0
        var ready by mutableStateOf(false)
        setContent {
            Box(Modifier.size(10.dp).submitOnCmdEnter(ready) { sent++ }.testTag("k").focusable())
        }
        val k = onNodeWithTag("k")
        k.requestFocus()
        k.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } }
        assertEquals(0, sent, "not while the button would not")
        ready = true
        waitForIdle()
        k.performKeyInput { pressKey(Key.Enter) }
        k.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } }
        k.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.A) } }
        assertEquals(0, sent, "Enter alone is a line in the body")
        k.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } }
        assertEquals(1, sent, "once, not on the key's release too")
        k.performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.NumPadEnter) } }
        assertEquals(2, sent)
    }
}
