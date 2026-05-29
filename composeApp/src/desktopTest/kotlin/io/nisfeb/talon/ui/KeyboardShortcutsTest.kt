@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package io.nisfeb.talon.ui

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyboardShortcutsTest {

    @Test
    fun `Ctrl+K maps to FocusSearch`() {
        val ev = ctrlKeyDown(Key.K)
        assertEquals(ShortcutAction.FocusSearch, keyEventToShortcut(ev))
    }

    @Test
    fun `Ctrl+N maps to NewDm`() {
        assertEquals(ShortcutAction.NewDm, keyEventToShortcut(ctrlKeyDown(Key.N)))
    }

    @Test
    fun `Esc with no modifiers maps to Back`() {
        assertEquals(ShortcutAction.Back, keyEventToShortcut(plainKeyDown(Key.Escape)))
    }

    @Test
    fun `Ctrl+Comma maps to OpenSettings`() {
        assertEquals(ShortcutAction.OpenSettings, keyEventToShortcut(ctrlKeyDown(Key.Comma)))
    }

    @Test
    fun `Ctrl+1 through Ctrl+9 map to SwitchShip 0 to 8`() {
        val pairs = listOf(
            Key.One to 0, Key.Two to 1, Key.Three to 2, Key.Four to 3,
            Key.Five to 4, Key.Six to 5, Key.Seven to 6, Key.Eight to 7,
            Key.Nine to 8,
        )
        for ((k, idx) in pairs) {
            assertEquals(ShortcutAction.SwitchShip(idx), keyEventToShortcut(ctrlKeyDown(k)))
        }
    }

    @Test
    fun `KeyUp is ignored`() {
        val ev = ctrlKeyUp(Key.K)
        assertNull(keyEventToShortcut(ev))
    }

    @Test
    fun `Ctrl+Shift+K is ignored - reserved for editor`() {
        assertNull(keyEventToShortcut(ctrlShiftKeyDown(Key.K)))
    }

    @Test
    fun `Ctrl+Equals maps to IncreaseFontSize`() {
        assertEquals(ShortcutAction.IncreaseFontSize, keyEventToShortcut(ctrlKeyDown(Key.Equals)))
    }

    @Test
    fun `Ctrl+Shift+Equals (the plus) still maps to IncreaseFontSize`() {
        // "+" is Shift+"=" on US layouts; the Shift guard must not
        // swallow this one even though it blocks other Ctrl+Shift combos.
        assertEquals(
            ShortcutAction.IncreaseFontSize,
            keyEventToShortcut(ctrlShiftKeyDown(Key.Equals)),
        )
    }

    @Test
    fun `Ctrl+Minus maps to DecreaseFontSize`() {
        assertEquals(ShortcutAction.DecreaseFontSize, keyEventToShortcut(ctrlKeyDown(Key.Minus)))
    }

    @Test
    fun `Ctrl+0 maps to ResetFontSize`() {
        assertEquals(ShortcutAction.ResetFontSize, keyEventToShortcut(ctrlKeyDown(Key.Zero)))
    }

    @Test
    fun `Ctrl+Shift+K still ignored after font-zoom carve-out`() {
        // Regression guard: the Shift bypass is scoped to the zoom keys
        // only; other Ctrl+Shift combos must still pass through.
        assertNull(keyEventToShortcut(ctrlShiftKeyDown(Key.K)))
    }

    @Test
    fun `Cmd+Equals maps to IncreaseFontSize on macOS`() {
        assertEquals(
            ShortcutAction.IncreaseFontSize,
            keyEventToShortcut(metaKeyDown(Key.Equals), isMacHost = true),
        )
    }

    @Test
    fun `macOS Cmd+K maps via the meta branch`() {
        assertEquals(ShortcutAction.FocusSearch, keyEventToShortcut(metaKeyDown(Key.K), isMacHost = true))
    }

    @Test
    fun `Ctrl+K does not map when isMacHost is true`() {
        assertNull(keyEventToShortcut(ctrlKeyDown(Key.K), isMacHost = true))
    }

    // Use the CMP 1.7 desktop KeyEvent factory. The old approach of wrapping
    // java.awt.event.KeyEvent no longer works — in CMP 1.7 KeyEvent is backed
    // by InternalKeyEvent (Skiko), not AWT directly. The factory requires
    // @OptIn(InternalComposeUiApi) declared at file scope above.
    private fun makeEvent(
        key: Key,
        isCtrl: Boolean = false,
        isMeta: Boolean = false,
        isShift: Boolean = false,
        isAlt: Boolean = false,
        type: KeyEventType = KeyEventType.KeyDown,
    ): KeyEvent = KeyEvent(
        key = key,
        type = type,
        codePoint = 0,
        isCtrlPressed = isCtrl,
        isMetaPressed = isMeta,
        isShiftPressed = isShift,
        isAltPressed = isAlt,
    )

    private fun ctrlKeyDown(k: Key) = makeEvent(k, isCtrl = true)
    private fun ctrlKeyUp(k: Key) = makeEvent(k, isCtrl = true, type = KeyEventType.KeyUp)
    private fun ctrlShiftKeyDown(k: Key) = makeEvent(k, isCtrl = true, isShift = true)
    private fun metaKeyDown(k: Key) = makeEvent(k, isMeta = true)
    private fun plainKeyDown(k: Key) = makeEvent(k)
}
