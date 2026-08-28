package io.nisfeb.talon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.call.AudioDevices

/**
 * Microphone and speaker pickers for a call in progress.
 *
 * Collapsed by default and absent entirely where the OS owns routing
 * (see [AudioDevices.supported]). This sits over a conversation, so an
 * always-open pair of lists would cost more rows than the call itself.
 *
 * Devices are read when the pane opens rather than held in state: the
 * list changes when someone plugs in a headset mid-call, and the moment
 * of opening the pane is exactly when a stale list would be noticed.
 */
@Composable
fun AudioDeviceControls(
    devices: AudioDevices,
    modifier: Modifier = Modifier,
) {
    if (!devices.supported) return
    var open by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { open = !open },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Microphone and speaker",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (open) "Hide audio devices" else "Choose audio devices",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (open) {
                // Read once per open, not per recomposition: each call
                // crosses into the native device layer.
                val inputs = remember(open) { devices.inputs() }
                val outputs = remember(open) { devices.outputs() }
                var input by remember { mutableStateOf(devices.selectedInput) }
                var output by remember { mutableStateOf(devices.selectedOutput) }

                DevicePicker(
                    title = "Microphone",
                    devices = inputs,
                    selected = input,
                    onPick = { input = it; devices.selectInput(it) },
                )
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                DevicePicker(
                    title = "Speaker",
                    devices = outputs,
                    selected = output,
                    onPick = { output = it; devices.selectOutput(it) },
                )
            }
        }
    }
}

@Composable
private fun DevicePicker(
    title: String,
    devices: List<io.nisfeb.talon.call.AudioDevice>,
    selected: String?,
    onPick: (String?) -> Unit,
) {
    Text(
        title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
    if (devices.isEmpty()) {
        Text(
            "No devices found.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    // "System default" stays first and reachable: it is where everyone
    // starts, and a user who picks a headset then unplugs it needs a
    // way back that doesn't involve guessing which entry is the laptop.
    DeviceRow("System default", selected == null) { onPick(null) }
    for (d in devices) {
        DeviceRow(d.label, selected == d.id) { onPick(d.id) }
    }
}

@Composable
private fun DeviceRow(label: String, chosen: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (chosen) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "Selected",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        } else {
            Spacer(Modifier.size(14.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (chosen) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
