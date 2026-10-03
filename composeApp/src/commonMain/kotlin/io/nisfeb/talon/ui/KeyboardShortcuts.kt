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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable

/**
 * Discrete actions the keyboard-shortcut layer can request. Hosts
 * (App.kt today) interpret each by flipping their existing state
 * setters or new request flags.
 */
sealed interface ShortcutAction {
    data object FocusSearch : ShortcutAction
    data object NewDm : ShortcutAction
    data object Back : ShortcutAction
    data class SwitchShip(val index: Int) : ShortcutAction

    /** One of the app's areas, opened as its rail item or drawer row opens it. */
    data class Open(val item: RailItem) : ShortcutAction

    /** Ctrl/Cmd + `=` / `+` — bump the app font scale up one step. */
    data object IncreaseFontSize : ShortcutAction

    /** Ctrl/Cmd + `-` — drop the app font scale one step. */
    data object DecreaseFontSize : ShortcutAction

    /** Ctrl/Cmd + `0` — reset the font scale to 1.0. */
    data object ResetFontSize : ShortcutAction
}

/**
 * A key and its modifiers as the owner set them, kept by the key's name
 * ("K", "F5", "Comma") so it reads the same on every platform.
 */
@Serializable
data class KeyCombo(
    val key: String,
    val ctrl: Boolean = false,
    val meta: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
) {
    /** "Ctrl+Alt+M" off a Mac, "⌥⌘M" on one. */
    fun label(isMacHost: Boolean): String = buildString {
        if (isMacHost) {
            if (ctrl) append('⌃'); if (alt) append('⌥'); if (shift) append('⇧'); if (meta) append('⌘')
        } else {
            if (ctrl) append("Ctrl+"); if (meta) append("Super+"); if (alt) append("Alt+"); if (shift) append("Shift+")
        }
        append(KEY_LABELS[key] ?: key)
    }
}

/** The keys a binding may use, by the name a [KeyCombo] keeps. */
private val NAMED_KEYS: List<Pair<String, Key>> = listOf(
    "A" to Key.A, "B" to Key.B, "C" to Key.C, "D" to Key.D, "E" to Key.E, "F" to Key.F, "G" to Key.G,
    "H" to Key.H, "I" to Key.I, "J" to Key.J, "K" to Key.K, "L" to Key.L, "M" to Key.M, "N" to Key.N,
    "O" to Key.O, "P" to Key.P, "Q" to Key.Q, "R" to Key.R, "S" to Key.S, "T" to Key.T, "U" to Key.U,
    "V" to Key.V, "W" to Key.W, "X" to Key.X, "Y" to Key.Y, "Z" to Key.Z,
    "0" to Key.Zero, "1" to Key.One, "2" to Key.Two, "3" to Key.Three, "4" to Key.Four,
    "5" to Key.Five, "6" to Key.Six, "7" to Key.Seven, "8" to Key.Eight, "9" to Key.Nine,
    "F1" to Key.F1, "F2" to Key.F2, "F3" to Key.F3, "F4" to Key.F4, "F5" to Key.F5, "F6" to Key.F6,
    "F7" to Key.F7, "F8" to Key.F8, "F9" to Key.F9, "F10" to Key.F10, "F11" to Key.F11, "F12" to Key.F12,
    "Comma" to Key.Comma, "Period" to Key.Period, "Slash" to Key.Slash, "Semicolon" to Key.Semicolon,
    "Apostrophe" to Key.Apostrophe, "LeftBracket" to Key.LeftBracket, "RightBracket" to Key.RightBracket,
    "Backslash" to Key.Backslash, "Minus" to Key.Minus, "Equals" to Key.Equals, "Grave" to Key.Grave,
    "Space" to Key.Spacebar, "Enter" to Key.Enter, "Tab" to Key.Tab, "Backspace" to Key.Backspace,
    "Delete" to Key.Delete, "Home" to Key.MoveHome, "End" to Key.MoveEnd,
    "PageUp" to Key.PageUp, "PageDown" to Key.PageDown,
    "Up" to Key.DirectionUp, "Down" to Key.DirectionDown, "Left" to Key.DirectionLeft, "Right" to Key.DirectionRight,
)
private val KEY_NAMES: Map<Key, String> = NAMED_KEYS.associate { (n, k) -> k to n }

private val KEY_LABELS: Map<String, String> = mapOf(
    "Comma" to ",", "Period" to ".", "Slash" to "/", "Semicolon" to ";", "Apostrophe" to "'",
    "LeftBracket" to "[", "RightBracket" to "]", "Backslash" to "\\", "Minus" to "-", "Equals" to "=",
    "Grave" to "`", "PageUp" to "Page Up", "PageDown" to "Page Down",
    "Up" to "↑", "Down" to "↓", "Left" to "←", "Right" to "→",
)

/** The combo a key press makes, or null for a key no binding may use. */
fun comboOf(event: KeyEvent): KeyCombo? = KEY_NAMES[event.key]?.let {
    KeyCombo(it, event.isCtrlPressed, event.isMetaPressed, event.isAltPressed, event.isShiftPressed)
}

/** Whether [key] is a modifier on its own, pressed on the way to a combo. */
fun isModifierKey(key: Key): Boolean = key in setOf(
    Key.CtrlLeft, Key.CtrlRight, Key.MetaLeft, Key.MetaRight,
    Key.AltLeft, Key.AltRight, Key.ShiftLeft, Key.ShiftRight,
)

/** What may be bound: search, a new message, and each of the app's areas. Ids are kept in settings. */
val BINDABLE: List<Triple<String, String, ShortcutAction>> = listOf(
    Triple("search", "Search", ShortcutAction.FocusSearch),
    Triple("new-message", "New message", ShortcutAction.NewDm),
) + RailItem.entries.map { item ->
    Triple("open:${item.name}", if (item == RailItem.Chats) "Messages" else item.name, ShortcutAction.Open(item))
}

private fun primary(key: String, isMacHost: Boolean) =
    if (isMacHost) KeyCombo(key, meta = true) else KeyCombo(key, ctrl = true)

/** The bindings a device starts with: the three there always were. */
fun defaultKeybinds(isMacHost: Boolean): Map<String, KeyCombo> = mapOf(
    "search" to primary("K", isMacHost),
    "new-message" to primary("N", isMacHost),
    "open:${RailItem.Settings.name}" to primary("Comma", isMacHost),
)

/** The defaults with what the owner set over them; a null they set unbinds. */
fun effectiveKeybinds(stored: Map<String, KeyCombo?>, isMacHost: Boolean): Map<String, KeyCombo> =
    buildMap {
        putAll(defaultKeybinds(isMacHost))
        for ((id, combo) in stored) if (combo == null) remove(id) else put(id, combo)
    }

/**
 * Why [combo] may not be bound, or null when it may: a key on its own is
 * typing, and the ship and text-size keys are fixed.
 */
fun refusalFor(combo: KeyCombo, isMacHost: Boolean): String? {
    val held = combo.ctrl || combo.meta || combo.alt
    if (!held && !combo.key.matches(Regex("F\\d+"))) return "Hold Ctrl, ⌘ or Alt with it: a key on its own is typing."
    val primaryOnly = (if (isMacHost) combo.meta && !combo.ctrl else combo.ctrl && !combo.meta) && !combo.alt
    if (primaryOnly && !combo.shift && combo.key in setOf("1", "2", "3", "4", "5", "6", "7", "8", "9")) return "${combo.label(isMacHost)} switches ships."
    if (primaryOnly && combo.key in setOf("Equals", "Minus", "0")) return "${combo.label(isMacHost)} sizes text."
    return null
}

/**
 * Pure mapping from a [KeyEvent] to a [ShortcutAction]. Returns null
 * for any event that doesn't match — caller passes the event through
 * to the focused widget unchanged.
 *
 * - macOS uses Cmd (`isMetaPressed`); other platforms use Ctrl.
 * - Only KeyDown events trigger; key-up is ignored so a held key
 *   fires once per press.
 * - Ship switching (primary + 1-9), text size (primary + = - 0) and
 *   Esc are fixed. Everything else is what [binds] says, the defaults
 *   ([defaultKeybinds]) unless the owner set their own.
 */
fun keyEventToShortcut(
    event: KeyEvent,
    isMacHost: Boolean = false,
    binds: Map<String, KeyCombo> = defaultKeybinds(isMacHost),
): ShortcutAction? {
    if (event.type != KeyEventType.KeyDown) return null
    val modifierActive = if (isMacHost) event.isMetaPressed else event.isCtrlPressed

    // Font-size zoom is handled before the Shift guard below: on US
    // layouts "+" is Shift+"=", so Ctrl+"+" arrives with Shift set.
    if (modifierActive && !event.isAltPressed) {
        when (event.key) {
            Key.Equals, Key.Plus, Key.NumPadAdd -> return ShortcutAction.IncreaseFontSize
            Key.Minus, Key.NumPadSubtract -> return ShortcutAction.DecreaseFontSize
            Key.Zero, Key.NumPad0 -> return ShortcutAction.ResetFontSize
            else -> Unit
        }
    }
    val bare = !event.isCtrlPressed && !event.isMetaPressed && !event.isAltPressed && !event.isShiftPressed
    if (event.key == Key.Escape && bare) return ShortcutAction.Back
    if (modifierActive && !event.isShiftPressed && !event.isAltPressed) {
        when (event.key) {
            Key.One -> return ShortcutAction.SwitchShip(0)
            Key.Two -> return ShortcutAction.SwitchShip(1)
            Key.Three -> return ShortcutAction.SwitchShip(2)
            Key.Four -> return ShortcutAction.SwitchShip(3)
            Key.Five -> return ShortcutAction.SwitchShip(4)
            Key.Six -> return ShortcutAction.SwitchShip(5)
            Key.Seven -> return ShortcutAction.SwitchShip(6)
            Key.Eight -> return ShortcutAction.SwitchShip(7)
            Key.Nine -> return ShortcutAction.SwitchShip(8)
            else -> Unit
        }
    }
    val combo = comboOf(event) ?: return null
    return actionFor(combo, binds)
}

/** The action [combo] is bound to in [binds], or null. */
fun actionFor(combo: KeyCombo, binds: Map<String, KeyCombo>): ShortcutAction? {
    val id = binds.entries.firstOrNull { it.value == combo }?.key ?: return null
    return BINDABLE.firstOrNull { it.first == id }?.third
}

/**
 * [stored] with [id] bound to [combo], taken from whichever action had
 * it, and that action's label (or null when it was free).
 */
fun rebind(stored: Map<String, KeyCombo?>, id: String, combo: KeyCombo, isMacHost: Boolean): Pair<Map<String, KeyCombo?>, String?> {
    val holder = effectiveKeybinds(stored, isMacHost).entries.firstOrNull { it.value == combo && it.key != id }?.key
    val next = stored.toMutableMap()
    next[id] = combo
    if (holder != null) next[holder] = null
    return next to holder?.let { h -> BINDABLE.firstOrNull { it.first == h }?.second }
}

/** The fixed shortcuts, as Settings lists them under the ones that can be set. */
fun fixedShortcuts(isMacHost: Boolean): List<Pair<String, String>> {
    val mod = if (isMacHost) "⌘" else "Ctrl+"
    return listOf(
        "${mod}1 to ${mod}9" to "Switch to a ship",
        "$mod= and $mod-" to "Larger and smaller text",
        "${mod}0" to "Text at its usual size",
        "Esc" to "Back",
    )
}

/**
 * Whether Settings is taking down a new shortcut. The app's own handler
 * stands aside meanwhile, or the keys pressed for it would run whatever
 * they are bound to now.
 */
object KeybindCapture {
    var active by androidx.compose.runtime.mutableStateOf(false)
}

