package io.nisfeb.talon.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Detect right-click (PointerButton.Secondary) and fire [onClick].
 *
 * Listens on the Main pass so nested handlers dispatch inner-first:
 * a right-click on a reaction chip inside a message bubble fires the
 * chip's onClick, the chip consumes the event, and the outer bubble's
 * handler sees the consumed event and skips. (Initial pass goes
 * outer→inner, which had the bubble's action sheet popping open
 * underneath the reactions list — both popups stacked.)
 *
 * The change-consumed check also stops Compose's `combinedClickable`
 * from re-interpreting the right press as a primary click on
 * shared-modifier nodes — that was the original reason for the
 * Initial-pass setup, and consuming early in Main preserves it.
 */
@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.onSecondaryClick(onClick: () -> Unit): Modifier =
    this.pointerInput(onClick) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                if (event.changes.any { it.isConsumed }) continue
                if (event.type == PointerEventType.Press &&
                    event.button == PointerButton.Secondary
                ) {
                    event.changes.forEach { it.consume() }
                    onClick()
                }
            }
        }
    }
