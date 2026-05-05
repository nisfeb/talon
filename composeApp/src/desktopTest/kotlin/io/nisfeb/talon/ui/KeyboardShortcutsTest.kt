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
    fun `macOS Cmd+K maps via the meta branch`() {
        assertEquals(ShortcutAction.FocusSearch, keyEventToShortcut(metaKeyDown(Key.K), isMacHost = true))
    }

    @Test
    fun `Ctrl+K does not map when isMacHost is true`() {
        assertNull(keyEventToShortcut(ctrlKeyDown(Key.K), isMacHost = true))
    }

    // Hardcode the AWT virtual key codes for the keys we test.
    // Compose's Key.keyCode packing has changed between versions
    // (high-bits vs low-bits) so deriving them at runtime is fragile;
    // a small lookup table is stable across upgrades.
    private val awtCodeFor: Map<Key, Int> = mapOf(
        Key.K to java.awt.event.KeyEvent.VK_K,
        Key.N to java.awt.event.KeyEvent.VK_N,
        Key.Comma to java.awt.event.KeyEvent.VK_COMMA,
        Key.Escape to java.awt.event.KeyEvent.VK_ESCAPE,
        Key.One to java.awt.event.KeyEvent.VK_1,
        Key.Two to java.awt.event.KeyEvent.VK_2,
        Key.Three to java.awt.event.KeyEvent.VK_3,
        Key.Four to java.awt.event.KeyEvent.VK_4,
        Key.Five to java.awt.event.KeyEvent.VK_5,
        Key.Six to java.awt.event.KeyEvent.VK_6,
        Key.Seven to java.awt.event.KeyEvent.VK_7,
        Key.Eight to java.awt.event.KeyEvent.VK_8,
        Key.Nine to java.awt.event.KeyEvent.VK_9,
    )

    // Use the CMP 1.7 desktop KeyEvent factory (key: Long, type: Int,
    // codePoint: Int, isAltPressed, isCtrlPressed, isMetaPressed,
    // isShiftPressed, nativeEvent). Requires @OptIn(InternalComposeUiApi)
    // declared at file scope above.
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
