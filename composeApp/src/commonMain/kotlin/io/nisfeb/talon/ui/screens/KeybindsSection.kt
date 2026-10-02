package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.ui.BINDABLE
import io.nisfeb.talon.ui.KeybindCapture
import io.nisfeb.talon.ui.OutlinedButton
import io.nisfeb.talon.ui.TextButton
import io.nisfeb.talon.ui.UiSettings
import io.nisfeb.talon.ui.comboOf
import io.nisfeb.talon.ui.effectiveKeybinds
import io.nisfeb.talon.ui.fixedShortcuts
import io.nisfeb.talon.ui.isModifierKey
import io.nisfeb.talon.ui.rebind
import io.nisfeb.talon.ui.refusalFor
import io.nisfeb.talon.util.isMacOsHost

/**
 * The keyboard shortcuts, each set by clicking it and pressing its keys:
 * search, a new message, and every area of the app. A combo another
 * action had moves to this one, and says so. The ship, text-size and Esc
 * keys are fixed and listed after.
 */
@Composable
internal fun KeybindsSection(uiSettings: UiSettings, isMacHost: Boolean = isMacOsHost) {
    val stored by uiSettings.keybinds.collectAsState()
    val binds = remember(stored, isMacHost) { effectiveKeybinds(stored, isMacHost) }
    var recording by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    DisposableEffect(recording) {
        KeybindCapture.active = recording != null
        onDispose { KeybindCapture.active = false }
    }
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant

    Text(
        "Keyboard shortcuts",
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(top = 4.dp),
    )
    Text("Click one, then press its keys. Esc stops.", style = MaterialTheme.typography.bodySmall, color = quiet)
    BINDABLE.forEach { (id, label, _) ->
        val combo = binds[id]
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(150.dp))
            OutlinedButton(onClick = { note = null; recording = if (recording == id) null else id }) {
                Text(
                    when {
                        recording == id -> "Press keys…"
                        combo != null -> combo.label(isMacHost)
                        else -> "None"
                    },
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (combo != null && recording != id) {
                io.nisfeb.talon.ui.IconButton(
                    tip = "Remove the shortcut for $label",
                    onClick = { note = null; uiSettings.setKeybinds(stored + (id to null)) },
                ) { Icon(Icons.Filled.Close, contentDescription = "Remove the shortcut for $label") }
            }
        }
    }
    recording?.let { id ->
        // The keys go here while it listens; the app's handler stands aside.
        val focus = remember(id) { FocusRequester() }
        var held by remember(id) { mutableStateOf(false) }
        LaunchedEffect(id) { runCatching { focus.requestFocus() } }
        Box(
            Modifier.size(1.dp)
                .focusRequester(focus)
                .onFocusChanged { if (it.isFocused) held = true else if (held) recording = null }
                .focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown || isModifierKey(e.key)) return@onPreviewKeyEvent true
                    val bare = !e.isCtrlPressed && !e.isMetaPressed && !e.isAltPressed && !e.isShiftPressed
                    if (e.key == Key.Escape && bare) {
                        recording = null
                        return@onPreviewKeyEvent true
                    }
                    val combo = comboOf(e)
                    val refused = if (combo == null) "That key can't be a shortcut." else refusalFor(combo, isMacHost)
                    if (combo == null || refused != null) {
                        note = refused
                        return@onPreviewKeyEvent true
                    }
                    val (next, from) = rebind(stored, id, combo, isMacHost)
                    uiSettings.setKeybinds(next)
                    note = from?.let { "${combo.label(isMacHost)} was for $it, and is for ${BINDABLE.first { b -> b.first == id }.second} now." }
                    recording = null
                    true
                },
        )
    }
    note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = quiet) }
    if (stored.isNotEmpty()) {
        TextButton(onClick = { note = null; recording = null; uiSettings.setKeybinds(emptyMap()) }) { Text("Restore the defaults") }
    }
    fixedShortcuts(isMacHost).forEach { (keys, does) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(does, style = MaterialTheme.typography.bodyMedium, color = quiet, modifier = Modifier.width(150.dp))
            Text(keys, style = MaterialTheme.typography.bodyMedium, color = quiet, fontFamily = FontFamily.Monospace)
        }
    }
}
