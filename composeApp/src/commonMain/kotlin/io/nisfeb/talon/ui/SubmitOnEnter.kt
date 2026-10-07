package io.nisfeb.talon.ui

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction

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

/** The soft keyboard's own Go key, doing the same. */
val GoKeyboard = KeyboardOptions(imeAction = ImeAction.Go)

fun goActions(onSubmit: () -> Unit) = KeyboardActions(onGo = { onSubmit() })
