package io.nisfeb.talon.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Detect right-click (PointerButton.Secondary) and fire [onClick].
 * Runs on the Initial pass so the click is consumed before any
 * underlying combinedClickable sees it — otherwise the right press
 * also registers as a primary click.
 */
@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.onSecondaryClick(onClick: () -> Unit): Modifier =
    this.pointerInput(onClick) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press &&
                    event.button == PointerButton.Secondary
                ) {
                    event.changes.forEach { it.consume() }
                    onClick()
                }
            }
        }
    }
