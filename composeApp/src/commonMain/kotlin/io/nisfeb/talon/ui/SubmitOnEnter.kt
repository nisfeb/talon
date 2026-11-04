package io.nisfeb.talon.ui

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Enter does what the form's button does, where the field is a ship's
 * name: a multi-line field (a comet's twelve words have room) took Enter
 * as a new line no name has, and the mouse had to fetch the button.
 * Shift+Enter still breaks a line. Nothing happens while [enabled] is
 * false, as the button would not.
 */
fun Modifier.submitOnEnter(enabled: Boolean, onSubmit: () -> Unit): Modifier = onPreviewKeyEvent { e ->
    val enter = e.key == Key.Enter || e.key == Key.NumPadEnter
    if (!enter || e.isShiftPressed) return@onPreviewKeyEvent false
    if (e.type == KeyEventType.KeyDown && enabled) onSubmit()
    true
}

/**
 * A comment box sends on Enter, as the chat composer does, Cmd or Ctrl
 * held or not; a comment under a post took Enter as a new line and only
 * the button sent it. Shift+Enter breaks the line, which a desktop field
 * does not do by itself. An input method's keys stay its own while it
 * composes: Enter confirms its candidate.
 */
fun Modifier.sendOnEnter(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    enabled: Boolean,
    onSend: () -> Unit,
): Modifier = onPreviewKeyEvent { e ->
    val enter = e.key == Key.Enter || e.key == Key.NumPadEnter
    if (!enter || value.composition != null) return@onPreviewKeyEvent false
    if (e.type == KeyEventType.KeyDown) {
        if (e.isShiftPressed) {
            val at = value.selection.min
            onValueChange(value.copy(text = value.text.replaceRange(at, value.selection.max, "\n"), selection = TextRange(at + 1)))
        } else if (enabled) {
            onSend()
        }
    }
    true
}

/**
 * Cmd+Enter (Ctrl+Enter off a Mac) does what a post form's button does,
 * where plain Enter must stay a new line in the body. On the form, so it
 * works from any of its fields.
 */
fun Modifier.submitOnCmdEnter(enabled: Boolean, onSubmit: () -> Unit): Modifier = onPreviewKeyEvent { e ->
    val enter = e.key == Key.Enter || e.key == Key.NumPadEnter
    if (!enter || !(e.isCtrlPressed || e.isMetaPressed)) return@onPreviewKeyEvent false
    if (e.type == KeyEventType.KeyDown && enabled) onSubmit()
    true
}

/** The soft keyboard's own Go key, doing the same. */
val GoKeyboard = KeyboardOptions(imeAction = ImeAction.Go)

fun goActions(onSubmit: () -> Unit) = KeyboardActions(onGo = { onSubmit() })
