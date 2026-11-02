package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.call.AudioDevices
import io.nisfeb.talon.call.VideoDevices

/** One line of a call's ⋯ menu. */
data class CallMenuItem(val label: String, val onClick: () -> Unit)

/**
 * A call's ⋯: everything but mute, camera, screen share and leave, which
 * keep buttons of their own (sneagan, 2026-11-02: "there are just too
 * many buttons in the talking UI"). [trigger] draws the button the
 * surface's way and calls its argument to open the menu. Nothing is
 * drawn without [items].
 */
@Composable
fun CallMenu(items: List<CallMenuItem>, trigger: @Composable (open: () -> Unit) -> Unit) {
    if (items.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        trigger { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (item in items) {
                DropdownMenuItem(text = { Text(item.label) }, onClick = { open = false; item.onClick() })
            }
        }
    }
}

/** Whether a call has devices to choose: the OS's audio routes, or cameras where [onSelectCamera] switches them. */
fun hasDeviceChoices(audioDevices: AudioDevices, videoDevices: VideoDevices, onSelectCamera: ((String) -> Unit)?): Boolean =
    audioDevices.supported || (onSelectCamera != null && videoDevices.supported)

/**
 * The call's microphone, speaker and camera, from its ⋯ menu. A dialog,
 * not a menu: a device's whole name fits, where the menu cut a long one
 * short.
 */
@Composable
fun DevicesDialog(
    audioDevices: AudioDevices,
    videoDevices: VideoDevices,
    onSelectCamera: ((String) -> Unit)?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Devices") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                DeviceChoices(audioDevices, videoDevices, onSelectCamera)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/**
 * A person on a call. A tap messages them; a guest, or ourselves, has
 * no one to message (sneagan, 2026-11-02: the ⋯ on each person was the
 * only way, and is gone). An admin's mute for everyone, or letting them
 * speak again, is a right-click or a long press, or a tap where there
 * is no one to message. [content] is the row.
 */
@Composable
internal fun CallPersonRow(
    ship: String,
    isSelf: Boolean,
    mutedByAdmin: Boolean,
    onMessage: ((String) -> Unit)?,
    /** We hold the line's admin rights and can moderate. */
    ops: Boolean,
    onRevokeSpeaking: ((String) -> Unit)?,
    onRestoreSpeaking: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val message = onMessage?.takeUnless { isSelf || io.nisfeb.talon.comet.isGuestName(ship) }
    val moderate = ops && !isSelf && onRevokeSpeaking != null && onRestoreSpeaking != null
    var menuOpen by remember(ship) { mutableStateOf(false) }
    Box {
        Row(
            modifier = if (message == null && !moderate) {
                modifier
            } else {
                modifier.combinedClickableWithSecondary(
                    onClick = { if (message != null) message(ship) else menuOpen = true },
                    onLongClick = if (moderate) ({ menuOpen = true }) else null,
                )
            },
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        if (moderate) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(if (mutedByAdmin) "Allow speaking" else "Mute for everyone") },
                    onClick = {
                        menuOpen = false
                        if (mutedByAdmin) onRestoreSpeaking?.invoke(ship) else onRevokeSpeaking?.invoke(ship)
                    },
                )
            }
        }
    }
}

