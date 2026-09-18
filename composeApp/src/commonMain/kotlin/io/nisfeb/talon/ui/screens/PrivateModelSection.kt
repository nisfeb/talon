package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.ai.AiSettingsRepository
import io.nisfeb.talon.orrery.LocalModels
import io.nisfeb.talon.orrery.OrreryRepo
import io.nisfeb.talon.orrery.RungStatus
import io.nisfeb.talon.ui.isLocalTriageSupported
import io.nisfeb.talon.ui.isTouchPrimary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The private model: the one that reads your messages.
 *
 * It is the other half of the AI settings, beside the frontier model,
 * and the difference between them is not where they run. Either can be
 * on this machine or across the world, and either may want a key. The
 * difference is what they are for: the frontier model writes summaries
 * and carries out what the analyst proposes, and this one reads what
 * people say to you, which is why it is the one that can be kept to a
 * machine you own.
 */
@Composable
fun PrivateModelSection(orrery: OrreryRepo?, aiSettings: AiSettingsRepository) {
    val scope = rememberCoroutineScope()
    val cfg by aiSettings.state.collectAsState()

    Spacer(Modifier.height(16.dp))
    Text("Private model", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
    Text(
        "Reads your messages. Leave it to this device, or point it at a server of your own.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // What the device itself can do, which is the floor under everything.
    // One collection either way, so the call site does not move about.
    val noModel = remember { MutableStateFlow<Pair<String, RungStatus>?>(null) }
    val noProgress = remember { MutableStateFlow<Float?>(null) }
    val model by (orrery?.model ?: noModel).collectAsState()
    val download by (orrery?.download ?: noProgress).collectAsState()
    var note by remember { mutableStateOf<String?>(null) }
    val line = when {
        !isLocalTriageSupported -> "No model on this platform yet. The rules alone read messages."
        model == null -> "Looking."
        model!!.second == RungStatus.Ready -> "Reads with ${model!!.first}."
        model!!.second is RungStatus.NeedsDownload ->
            "${model!!.first} can read messages here after a download of about " +
                "${(model!!.second as RungStatus.NeedsDownload).bytes / 1_000_000} MB."
        else -> (model!!.second as RungStatus.Unavailable).reason
    }
    Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    if (orrery != null && model?.second is RungStatus.NeedsDownload) {
        if (download != null) {
            LinearProgressIndicator(progress = { download!! }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        } else {
            TextButton(onClick = {
                scope.launch { orrery.prepareModel().onFailure { note = it.message ?: "The download did not finish." } }
            }) { Text("Download the model") }
        }
    }
    note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }

    if (!isTouchPrimary) {
        // A server of your own, here or on another machine you have.
        var url by remember(cfg.privateBaseUrl) { mutableStateOf(cfg.privateBaseUrl.orEmpty()) }
        var name by remember(cfg.privateModel) { mutableStateOf(cfg.privateModel.orEmpty()) }
        var key by remember(cfg.privateApiKey) { mutableStateOf(cfg.privateApiKey) }
        Spacer(Modifier.height(8.dp))
        Text(
            "LM Studio or Ollama on this computer, or another machine of yours. Leave the address empty to use whichever is running on its usual port, and the name empty to take that server's best.",
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
            value = name,
            onValueChange = { name = it },
            label = { Text("Model") },
            placeholder = { Text("the server's best, by name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("API key (only if it wants one)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        val dirty = url.trim() != cfg.privateBaseUrl.orEmpty() ||
            name.trim() != cfg.privateModel.orEmpty() ||
            key.trim() != cfg.privateApiKey
        if (dirty) {
            TextButton(onClick = {
                aiSettings.setPrivateModel(url.trim(), name.trim(), key.trim())
                scope.launch {
                    LocalModels.usePrivate(AiSettings.Slot(null, key.trim(), name.trim(), url.trim()))
                    orrery?.refreshModel()
                }
            }) { Text("Use this model") }
        }
    }

    // The one switch that names the cost.
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Let the frontier model read messages", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (cfg.hasKey()) {
                    "Off by default. On, every message read leaves this device for ${cfg.provider.label}. " +
                        "A larger model reads better; that is the trade."
                } else {
                    "Needs a frontier key above. Off until then."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = cfg.frontierReadsMessages && cfg.hasKey(),
            enabled = cfg.hasKey(),
            onCheckedChange = { want ->
                aiSettings.setFrontierReadsMessages(want)
                scope.launch { orrery?.refreshModel() }
            },
        )
    }

    if (orrery != null) LaunchedEffect(orrery) { orrery.refreshModel() }
}
