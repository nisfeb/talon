package io.nisfeb.talon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.call.AudioDevices

/**
 * The microphone, speaker and camera lists, in the Devices dialog of a
 * call's ⋯ menu ([DevicesDialog]). Read when shown: each read
 * crosses into the native device layer. A pick shows what the platform
 * then reports, so a route it refused is never claimed. [onSelectCamera]
 * null leaves the camera out.
 */
@Composable
internal fun DeviceChoices(
    devices: AudioDevices,
    videoDevices: io.nisfeb.talon.call.VideoDevices,
    onSelectCamera: ((String) -> Unit)?,
) {
    val inputs = remember { devices.inputs() }
    val outputs = remember { devices.outputs() }
    var input by remember { mutableStateOf(devices.selectedInput) }
    var output by remember { mutableStateOf(devices.selectedOutput) }

    if (!devices.supported) {
        // No audio choice here: only the camera, if any.
    } else if (devices.unifiedRoute) {
        // One choice drives both directions here, so one
        // list. Two would imply a freedom the phone
        // doesn't have.
        DevicePicker(
            title = "Route",
            devices = inputs,
            selected = input,
            onPick = {
                devices.selectInput(it)
                input = devices.selectedInput
                output = input
            },
        )
    } else {
        DevicePicker(
            title = "Microphone",
            devices = inputs,
            selected = input,
            onPick = { devices.selectInput(it); input = devices.selectedInput },
        )
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        DevicePicker(
            title = "Speaker",
            devices = outputs,
            selected = output,
            onPick = { devices.selectOutput(it); output = devices.selectedOutput },
        )
    }
    if (onSelectCamera != null && videoDevices.supported) {
        // The same rows for cameras; "System default" is the first one found.
        val cameras = remember {
            videoDevices.cameras().map { io.nisfeb.talon.call.AudioDevice(it.id, it.label) }
        }
        var camera by remember { mutableStateOf(videoDevices.selectedCamera) }
        if (devices.supported) HorizontalDivider(Modifier.padding(vertical = 4.dp))
        DevicePicker(
            title = "Camera",
            devices = cameras,
            selected = camera,
            onPick = { id ->
                // The camera in use, picked again, restarts nothing.
                if (id == camera) return@DevicePicker
                camera = id
                if (id == null) videoDevices.selectCamera(null) else onSelectCamera(id)
            },
        )
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
    // Bounded and scrollable: a machine with a dozen outputs used to
    // push the rows past the bottom of the panel with no way to reach them.
    Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
        DeviceRow("System default", selected == null) { onPick(null) }
        for (d in devices) {
            DeviceRow(d.label, selected == d.id) { onPick(d.id) }
        }
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
        // Wrapped, never cut: a long device name was cut off at the
        // edge of the menu it was in (sneagan, 2026-11-02).
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = if (chosen) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
