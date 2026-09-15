package io.nisfeb.talon.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** A pasted attachment replaces the text field, so its preview has to answer the keyboard itself. */
@OptIn(ExperimentalTestApi::class)
class AttachmentPreviewKeysTest {

    private fun keys(key: Key, sending: Boolean = false): Pair<Int, Int> {
        var sent = 0
        var discarded = 0
        runComposeUiTest {
            setContent {
                TalonTheme(darkTheme = false) {
                    AttachmentPreviewRow(
                        pending = PendingAttachment("x".encodeToByteArray(), "text/plain", "notes.txt", isImage = false),
                        sending = sending,
                        sendAccent = Color.Blue,
                        onCancel = { discarded++ },
                        onSend = { sent++ },
                    )
                }
            }
            waitForIdle()
            onRoot().performKeyInput { pressKey(key) }
            waitForIdle()
        }
        return sent to discarded
    }

    @Test
    fun `enter posts the pasted attachment`() = assertEquals(1 to 0, keys(Key.Enter))

    @Test
    fun `escape discards it`() = assertEquals(0 to 1, keys(Key.Escape))

    @Test
    fun `enter while it uploads does not post it twice`() = assertEquals(0 to 0, keys(Key.Enter, sending = true))
}
