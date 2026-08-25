package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import io.nisfeb.talon.call.CallController
import io.nisfeb.talon.call.CallUiState
import io.nisfeb.talon.call.MediaState
import kotlinx.coroutines.delay

/**
 * v0 call surface: a banner pinned by the caller (App hoists it above
 * the nav host). Deliberately spartan — the spike measures signaling,
 * not chrome. Ended states self-dismiss after a beat.
 */
@Composable
fun CallOverlay(controller: CallController, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    if (state is CallUiState.None) return

    // Popup so the banner floats above whatever screen is showing —
    // no layout restructuring in App, incoming calls surface anywhere.
    Popup(alignment = Alignment.TopCenter) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (val s = state) {
                is CallUiState.Outgoing -> {
                    Text("Calling ${s.peer}…", style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { controller.hangup() }) { Text("Cancel") }
                }
                is CallUiState.Incoming -> {
                    Text("${s.peer} is calling", style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { controller.accept() }) { Text("Answer") }
                        OutlinedButton(onClick = { controller.reject() }) { Text("Decline") }
                    }
                }
                is CallUiState.Active -> {
                    val label = when (s.media) {
                        MediaState.Live -> "On the line with ${s.peer}"
                        else -> "Connecting to ${s.peer}…"
                    }
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { controller.setMuted(!s.muted) }) {
                            Text(if (s.muted) "Unmute" else "Mute")
                        }
                        Button(
                            onClick = { controller.hangup() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        ) { Text("Hang up") }
                    }
                }
                is CallUiState.Ended -> {
                    Text("Call with ${s.peer}: ${s.reason}", style = MaterialTheme.typography.bodyLarge)
                    LaunchedEffect(s) {
                        delay(4_000)
                        controller.dismissEnded()
                    }
                }
                CallUiState.None -> {}
            }
        }
    }
    }
}
