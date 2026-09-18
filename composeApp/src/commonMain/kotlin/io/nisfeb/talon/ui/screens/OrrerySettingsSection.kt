package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.orrery.LocalModels
import io.nisfeb.talon.orrery.OrreryAvailability
import io.nisfeb.talon.orrery.OrreryRepo
import io.nisfeb.talon.orrery.RungStatus
import io.nisfeb.talon.ui.UiSettings
import io.nisfeb.talon.ui.isLocalTriageSupported
import io.nisfeb.talon.ui.isTouchPrimary
import kotlinx.coroutines.launch

/**
 * Orrery under Settings > AI: the pipe's switch, and on a phone the
 * choice to leave the reading to a computer. Which model does the
 * reading is set above, under Private model, because that is a
 * question about models rather than about this one app.
 */
@Composable
fun OrrerySettingsSection(orrery: OrreryRepo) {
    val scope = rememberCoroutineScope()
    val availability by orrery.availability.collectAsState()
    val on by orrery.enabled.collectAsState()
    val lastMs by orrery.lastPushMs.collectAsState()
    val pushing by orrery.pushing.collectAsState()
    val error by orrery.error.collectAsState()
    val model by orrery.model.collectAsState()
    val download by orrery.download.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    Spacer(Modifier.height(16.dp))
    Text("Orrery", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
    Text(
        "Orrery is the model of your world on your ship. What Talon feeds it, and which model reads your messages for it, are set here. That model is separate from the AI provider above: the provider writes summaries and carries out actions; the reader here runs on this device.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    if (availability != OrreryAvailability.PRESENT) {
        Text(
            when (availability) {
                OrreryAvailability.MISSING -> "Orrery is not on this ship. Install it from the Grubbery shell on your ship; the Apps page shows its state."
                OrreryAvailability.SIGNED_OUT -> "Signed out of the ship."
                else -> "Not asked yet whether this ship has Orrery."
            },
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }

    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Feed Orrery", style = MaterialTheme.typography.bodyLarge)
            Text(
                "The people in your contacts book, who wrote to you and on which day, and your calendar go to Orrery as facts, under a key made for this install. Nothing anyone said leaves this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else Switch(
            checked = on,
            onCheckedChange = { want ->
                note = null
                scope.launch {
                    busy = true
                    (if (want) orrery.enable() else orrery.disable()).onFailure { note = it.message ?: "Orrery did not answer." }
                    busy = false
                }
            },
        )
    }
    if (on) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (lastMs != null) "Pushed ${agoLabel(lastMs!!)}." else "Not pushed yet.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(enabled = !pushing, onClick = { scope.launch { orrery.push() } }) { Text(if (pushing) "Pushing" else "Push now") }
        }
    }
    (note ?: error)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }

    if (isTouchPrimary) orrery.standDown?.let { sd ->
        val standing by sd.on.collectAsState()
        val yielding by orrery.yielding.collectAsState()
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Leave reading to your computer", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (standing && yielding) "A computer running Talon has been on the job in the last two hours, so this phone is leaving the reading to its bigger model. Facts still go up from here."
                    else "When a computer running Talon has been on the job in the last two hours, this phone leaves the reading to its bigger model. Facts still go up from here.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = standing, onCheckedChange = { sd.set(it) })
        }
    }

    // The ladder's answer is asked for once the section is on screen.
    LaunchedEffect(orrery) { orrery.refreshModel() }
}
