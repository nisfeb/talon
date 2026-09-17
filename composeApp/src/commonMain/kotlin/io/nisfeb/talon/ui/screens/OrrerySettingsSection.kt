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
 * Orrery under Settings > AI: the pipe's switch, and the model that
 * reads messages for it. That model is its own configuration on
 * purpose. The AI provider above it is for summaries and for carrying
 * out the analyst's actions; what reads your messages is a local
 * model, chosen here, unless you say otherwise with the one switch
 * that names the cost.
 */
@Composable
fun OrrerySettingsSection(orrery: OrreryRepo, uiSettings: UiSettings) {
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

    Spacer(Modifier.height(12.dp))
    Text("Reads with", style = MaterialTheme.typography.bodyLarge)
    val modelLine = when {
        !isLocalTriageSupported -> "No local model on this platform yet. The rules alone read messages."
        model == null -> "Looking."
        model!!.second == RungStatus.Ready -> "Reads with ${model!!.first}."
        model!!.second is RungStatus.NeedsDownload -> "${model!!.first} can read messages here after a download of about ${(model!!.second as RungStatus.NeedsDownload).bytes / 1_000_000} MB."
        else -> (model!!.second as RungStatus.Unavailable).reason
    }
    Text(modelLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (model?.second is RungStatus.NeedsDownload) {
        if (download != null) LinearProgressIndicator(progress = { download!! }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        else TextButton(onClick = { scope.launch { orrery.prepareModel().onFailure { note = it.message ?: "The download did not finish." } } }) { Text("Download the model") }
    }

    if (!isTouchPrimary) {
        // The triage's own server. Empty fields look on the usual ports and
        // take the server's best model by name; anything typed is used as is.
        val savedUrl by uiSettings.orreryServerUrl.collectAsState()
        val savedModel by uiSettings.orreryServerModel.collectAsState()
        var url by remember(savedUrl) { mutableStateOf(savedUrl) }
        var modelName by remember(savedModel) { mutableStateOf(savedModel) }
        Spacer(Modifier.height(8.dp))
        Text("Local model server", style = MaterialTheme.typography.bodyMedium)
        Text(
            "LM Studio or Ollama on this computer, or another machine of yours. Leave both empty to use whichever is running on its usual port and its best model.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Server URL") },
            placeholder = { Text("http://localhost:1234") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        OutlinedTextField(
            value = modelName,
            onValueChange = { modelName = it },
            label = { Text("Model") },
            placeholder = { Text("the server's best, by name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        val dirty = url.trim() != savedUrl || modelName.trim() != savedModel
        if (dirty) {
            TextButton(onClick = {
                uiSettings.setOrreryServerUrl(url.trim())
                uiSettings.setOrreryServerModel(modelName.trim())
                LocalModels.serverUrl = url.trim()
                LocalModels.serverModel = modelName.trim()
                scope.launch { LocalModels.reset(); orrery.refreshModel() }
            }) { Text("Use this server") }
        }
    }

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

    orrery.cloud?.let { cloud ->
        val cloudOn by cloud.on.collectAsState()
        val hasKey = cloud.config().apiKey.isNotBlank()
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Read with the AI provider above instead", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (hasKey) "Every message the triage reads leaves this device for ${cloud.config().provider.label}. A larger model reads better; that is the trade."
                    else "Needs an API key above. Off until then.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = cloudOn && hasKey, enabled = hasKey, onCheckedChange = { want -> cloud.set(want); scope.launch { orrery.refreshModel() } })
        }
    }

    // The ladder's answer is asked for once the section is on screen.
    LaunchedEffect(orrery) { orrery.refreshModel() }
}
