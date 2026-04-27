package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.TalonApplication
import io.nisfeb.talon.ai.AiSettings

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = (LocalContext.current.applicationContext as TalonApplication)
    val aiState by app.aiSettings.state.collectAsState()

    var provider by remember { mutableStateOf(aiState.provider) }
    var apiKey by remember { mutableStateOf(aiState.apiKey) }
    var model by remember { mutableStateOf(aiState.model.orEmpty()) }
    var baseUrl by remember { mutableStateOf(aiState.baseUrl.orEmpty()) }
    var revealKey by remember { mutableStateOf(false) }
    var providerMenuOpen by remember { mutableStateOf(false) }

    val dirty = provider != aiState.provider ||
        apiKey != aiState.apiKey ||
        (model.ifBlank { null } != aiState.model) ||
        (baseUrl.ifBlank { null } != aiState.baseUrl)

    Column(modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Settings",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        HorizontalDivider()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Composer",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            val hideButtons by app.uiSettings.hideComposerButtons.collectAsState()
            FeatureToggleRow(
                label = "Hide upload buttons",
                description = "Reclaim composer space. Use /img, /file, and /mic instead.",
                enabled = hideButtons,
                onChange = { app.uiSettings.setHideComposerButtons(it) },
            )
            HorizontalDivider()
            Text(
                "AI",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                "Enable AI features by pasting an API key. Features are hidden when no key is set. The key is stored encrypted on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Provider picker
            Box {
                OutlinedButton(
                    onClick = { providerMenuOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(provider.label, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.ExpandMore, contentDescription = null)
                }
                DropdownMenu(
                    expanded = providerMenuOpen,
                    onDismissRequest = { providerMenuOpen = false },
                ) {
                    AiSettings.Provider.values().forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.label) },
                            onClick = {
                                provider = p
                                providerMenuOpen = false
                            },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = if (revealKey) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { revealKey = !revealKey }) {
                        Icon(
                            imageVector = if (revealKey) Icons.Filled.VisibilityOff
                            else Icons.Filled.Visibility,
                            contentDescription = if (revealKey) "Hide key" else "Show key",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = {
                    Text(
                        if (provider == AiSettings.Provider.Custom) "Model"
                        else "Model (optional)"
                    )
                },
                placeholder = {
                    Text(
                        when (provider) {
                            AiSettings.Provider.Anthropic -> "claude-sonnet-4-5-20250929"
                            AiSettings.Provider.OpenRouter -> "anthropic/claude-sonnet-4"
                            AiSettings.Provider.OpenAi -> "gpt-4o-mini"
                            AiSettings.Provider.Custom -> "e.g. llama-3.1-70b"
                        }
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (provider == AiSettings.Provider.Custom) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    placeholder = { Text("https://api.example.com/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "OpenAI-compatible endpoint. Accepts a base URL ending " +
                        "in `/v1` or a full `/v1/chat/completions` URL.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        app.aiSettings.update(
                            provider,
                            apiKey.trim(),
                            model.trim().ifBlank { null },
                            baseUrl.trim().ifBlank { null },
                        )
                    },
                    enabled = dirty,
                ) { Text("Save") }
                TextButton(
                    onClick = {
                        app.aiSettings.clear()
                        provider = AiSettings.Provider.Anthropic
                        apiKey = ""
                        model = ""
                        baseUrl = ""
                    },
                    enabled = aiState.hasKey(),
                ) { Text("Remove key") }
            }
            if (aiState.hasKey()) {
                Text(
                    "✓ AI is enabled",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                FeatureToggleRow(
                    label = "Sync AI settings across devices",
                    description = "Stores your provider, model, API key, and toggles in %settings on the ship. The API key will be on the ship — only enable if you trust the ship.",
                    enabled = aiState.syncEnabled,
                    onChange = { app.aiSettings.setSyncEnabled(it) },
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    "Cloud features",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
                AiSettings.Feature.values()
                    .filter { it.requiresCloudKey }
                    .forEach { feature ->
                        FeatureToggleRow(
                            label = feature.label,
                            description = feature.description,
                            enabled = aiFeatureEnabled(aiState, feature),
                            onChange = { app.aiSettings.setFeature(feature, it) },
                        )
                    }
            }

            // On-device features: always shown, no API key required.
            // Default OFF — every entry in here is opt-in.
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                "On-device features",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                "Run entirely on your phone — no API key, no data sent off device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AiSettings.Feature.values()
                .filter { !it.requiresCloudKey }
                .forEach { feature ->
                    FeatureToggleRow(
                        label = feature.label,
                        description = feature.description,
                        enabled = aiFeatureEnabled(aiState, feature),
                        onChange = { app.aiSettings.setFeature(feature, it) },
                    )
                }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            DailyDigestSection(app = app)
        }
    }
}

/** Single source of truth for "is this feature toggle on?" — keeps the
 *  Settings UI's toggle state in lockstep with the gates wired across
 *  the rest of the app. */
internal fun aiFeatureEnabled(state: AiSettings.Config, feature: AiSettings.Feature): Boolean =
    when (feature) {
        AiSettings.Feature.CatchMeUp -> state.catchMeUpEnabled
        AiSettings.Feature.EmojiReact -> state.emojiReactEnabled
        AiSettings.Feature.DailyDigest -> state.dailyDigestEnabled
        AiSettings.Feature.EntityActions -> state.entityActionsEnabled
        AiSettings.Feature.SemanticSearch -> state.semanticSearchEnabled
        AiSettings.Feature.TopicClusters -> state.topicClustersEnabled
        AiSettings.Feature.ImportantMessages -> state.importantMessagesEnabled
    }

@Composable
private fun FeatureToggleRow(
    label: String,
    description: String,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = onChange)
    }
}

@Composable
private fun DailyDigestSection(app: TalonApplication) {
    val ddState = app.dailyDigestSettings.state.collectAsState().value
    var showTimePicker by remember { mutableStateOf(false) }

    Text(
        "Daily digest",
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    )
    Text(
        "A morning brief at your chosen time: unread, watchword hits, and @mentions. The AI summary toggle is in Cloud features above; this section just controls the alarm.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(8.dp))

    // Enable toggle
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Enabled", style = MaterialTheme.typography.bodyLarge)
            if (ddState.enabled) {
                Text(
                    "Next: ${formatNextFire(ddState.hourOfDay, ddState.minuteOfDay)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Off",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = ddState.enabled,
            onCheckedChange = { app.dailyDigestSettings.setEnabled(it) },
        )
    }

    if (ddState.enabled) {
        // Time picker row
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Fire time", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = { showTimePicker = true }) {
                Text("%02d:%02d".format(ddState.hourOfDay, ddState.minuteOfDay))
            }
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = { app.dailyDigest.generateAndNotifyAsync("test-now") },
        ) {
            Text("Test now")
        }
    }

    if (showTimePicker) {
        DailyDigestTimePicker(
            initialHour = ddState.hourOfDay,
            initialMinute = ddState.minuteOfDay,
            onDismiss = { showTimePicker = false },
            onConfirm = { hour, minute ->
                app.dailyDigestSettings.setTime(hour, minute)
                showTimePicker = false
            },
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DailyDigestTimePicker(
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (hour: Int, minute: Int) -> Unit,
) {
    val state = androidx.compose.material3.rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = false,
    )
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        title = { Text("Digest time") },
        text = {
            androidx.compose.material3.TimePicker(state = state)
        },
    )
}

/** Format the configured fire time as "today HH:MM" / "tomorrow HH:MM"
 *  depending on whether it has passed already. Pure helper. */
private fun formatNextFire(hourOfDay: Int, minuteOfDay: Int): String {
    val now = java.time.LocalTime.now()
    val target = java.time.LocalTime.of(hourOfDay, minuteOfDay)
    val tomorrow = !now.isBefore(target)
    val prefix = if (tomorrow) "tomorrow" else "today"
    return "%s %02d:%02d".format(prefix, hourOfDay, minuteOfDay)
}
