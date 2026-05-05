package io.nisfeb.talon.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * Discrete actions the keyboard-shortcut layer can request. Hosts
 * (App.kt today) interpret each by flipping their existing state
 * setters or new request flags.
 */
sealed interface ShortcutAction {
    data object FocusSearch : ShortcutAction
    data object NewDm : ShortcutAction
    data object Back : ShortcutAction
    data object OpenSettings : ShortcutAction
    data class SwitchShip(val index: Int) : ShortcutAction
}

/**
 * Pure mapping from a [KeyEvent] to a [ShortcutAction]. Returns null
 * for any event that doesn't match — caller passes the event through
 * to the focused widget unchanged.
 *
 * - macOS uses Cmd (`isMetaPressed`); other platforms use Ctrl.
 *   The selector is determined by [isMacHost] so the same code path
 *   serves Android (always Ctrl), Linux/Windows desktop (Ctrl), and
 *   macOS desktop (Cmd).
 * - Only KeyDown events trigger; key-up is ignored so a held key
 *   fires once per press.
 * - Shift / Alt qualifiers must be absent — combinations like
 *   Ctrl+Shift+K are not bound here and pass through to the editor.
 */
fun keyEventToShortcut(event: KeyEvent, isMacHost: Boolean = false): ShortcutAction? {
    if (event.type != KeyEventType.KeyDown) return null
    if (event.isShiftPressed || event.isAltPressed) return null
    val modifierActive = if (isMacHost) event.isMetaPressed else event.isCtrlPressed
    if (!modifierActive && event.key != Key.Escape) return null
    return when (event.key) {
        Key.Escape -> ShortcutAction.Back
        Key.K -> ShortcutAction.FocusSearch
        Key.N -> ShortcutAction.NewDm
        Key.Comma -> ShortcutAction.OpenSettings
        Key.One -> ShortcutAction.SwitchShip(0)
        Key.Two -> ShortcutAction.SwitchShip(1)
        Key.Three -> ShortcutAction.SwitchShip(2)
        Key.Four -> ShortcutAction.SwitchShip(3)
        Key.Five -> ShortcutAction.SwitchShip(4)
        Key.Six -> ShortcutAction.SwitchShip(5)
        Key.Seven -> ShortcutAction.SwitchShip(6)
        Key.Eight -> ShortcutAction.SwitchShip(7)
        Key.Nine -> ShortcutAction.SwitchShip(8)
        else -> null
    }
}
