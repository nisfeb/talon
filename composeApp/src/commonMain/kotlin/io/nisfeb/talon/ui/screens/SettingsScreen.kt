package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.material3.FilterChip
import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.ai.AiSettingsRepository
import io.nisfeb.talon.ui.UiSettings
import io.nisfeb.talon.ui.isOnDeviceAiFeatureSupported
import io.nisfeb.talon.ui.isOnDeviceAiSupported
import io.nisfeb.talon.ui.theme.ThemePreference

@Composable
fun SettingsScreen(
    aiSettings: AiSettingsRepository,
    themePreference: ThemePreference,
    uiSettings: UiSettings,
    /** Whether the user is logged into 2+ ships. Drives the
     *  accent-color section's auto-default — multi-ship users land
     *  with the toggle on so they don't lose the per-ship pip / send
     *  tint they're used to. */
    multiShip: Boolean = false,
    /** Active ship's contact color (already parsed). Surfaced in the
     *  Accent panel as a preview swatch for the Profile mode. Null
     *  when the user hasn't set a color on their own contact. */
    profileAccentPreview: androidx.compose.ui.graphics.Color? = null,
    onBack: () -> Unit,
    /** Optional daily-digest config + alarm controls. Android wires
     *  the JSON-prefs-backed impl that drives AlarmManager; desktop
     *  passes null until a desktop scheduler lands and the section
     *  hides entirely. */
    dailyDigestSettings: io.nisfeb.talon.ai.DailyDigestSettings? = null,
    /** Optional Android-only "Test now" handler that fires the digest
     *  immediately. When null the button isn't rendered. */
    onTestDigest: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val aiState by aiSettings.state.collectAsState()
    val themeMode by themePreference.mode.collectAsState()
    val hideComposerButtons by uiSettings.hideComposerButtons.collectAsState()
    val accentSettings by uiSettings.accentSettings.collectAsState()
    val accentEnabled = io.nisfeb.talon.ui.AccentSettings
        .isEnabled(accentSettings, multiShip)
    var customHexInput by remember(accentSettings.customHex) {
        mutableStateOf(accentSettings.customHex.orEmpty())
    }

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
                "Appearance",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemePreference.Mode.values().forEach { mode ->
                    FilterChip(
                        selected = themeMode == mode,
                        onClick = { themePreference.setMode(mode) },
                        label = { Text(mode.label()) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            // ── Accent color ────────────────────────────────────────
            FeatureToggleRow(
                label = "Custom accent color",
                description = if (multiShip) {
                    "Tints the send icon, focused text fields, and per-" +
                        "ship pip. On for multi-ship users by default."
                } else {
                    "Tints the send icon, focused text fields, and " +
                        "other primary-colored UI. Off keeps the brand " +
                        "amber everywhere."
                },
                enabled = accentEnabled,
                onChange = { on ->
                    uiSettings.setAccentSettings(accentSettings.copy(enabled = on))
                },
            )
            if (accentEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = accentSettings.mode == io.nisfeb.talon.ui.AccentMode.Profile,
                        onClick = {
                            uiSettings.setAccentSettings(
                                accentSettings.copy(mode = io.nisfeb.talon.ui.AccentMode.Profile),
                            )
                        },
                        label = { Text("Profile color") },
                    )
                    FilterChip(
                        selected = accentSettings.mode == io.nisfeb.talon.ui.AccentMode.Custom,
                        onClick = {
                            uiSettings.setAccentSettings(
                                accentSettings.copy(mode = io.nisfeb.talon.ui.AccentMode.Custom),
                            )
                        },
                        label = { Text("Custom hex") },
                    )
                }
                when (accentSettings.mode) {
                    io.nisfeb.talon.ui.AccentMode.Profile -> {
                        AccentSwatchRow(
                            label = "Pulled from your %contacts profile",
                            color = profileAccentPreview,
                            fallbackHint = "Set a color on your profile to customize.",
                        )
                    }
                    io.nisfeb.talon.ui.AccentMode.Custom -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = customHexInput,
                                onValueChange = { customHexInput = it },
                                placeholder = { Text("#RRGGBB") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            val previewColor = remember(customHexInput) {
                                io.nisfeb.talon.ui.parseHexColor(customHexInput)
                            }
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        previewColor
                                            ?: MaterialTheme.colorScheme.surfaceVariant,
                                    ),
                            )
                            TextButton(
                                enabled = previewColor != null &&
                                    customHexInput != accentSettings.customHex,
                                onClick = {
                                    uiSettings.setAccentSettings(
                                        accentSettings.copy(customHex = customHexInput),
                                    )
                                },
                            ) { Text("Apply") }
                        }
                    }
                    io.nisfeb.talon.ui.AccentMode.Brand -> Unit
                }
            }
            Spacer(Modifier.height(4.dp))

            Text(
                "Composer",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            FeatureToggleRow(
                label = "Hide composer buttons",
                description = "Hide the image / file / mic buttons next to the message field. " +
                    "Useful when you mostly send plain text and want a tighter input row.",
                enabled = hideComposerButtons,
                onChange = { uiSettings.setHideComposerButtons(it) },
            )
            Spacer(Modifier.height(4.dp))

            Text(
                "AI",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                "Enable AI features by pasting an API key. Features are hidden when no key is set.",
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
                        aiSettings.update(
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
                        aiSettings.clear()
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
                    onChange = { aiSettings.setSyncEnabled(it) },
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
                            onChange = { aiSettings.setFeature(feature, it) },
                        )
                    }
            }

            // On-device features — gated behind isOnDeviceAiSupported.
            // True on Android (ML Kit + on-device embedder available);
            // false on desktop until / unless an equivalent stack lands.
            if (isOnDeviceAiSupported) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    "On-device features",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    "Run entirely on your device — no API key, no data sent off device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AiSettings.Feature.values()
                    .filter { !it.requiresCloudKey }
                    .filter { isOnDeviceAiFeatureSupported(it) }
                    .forEach { feature ->
                        FeatureToggleRow(
                            label = feature.label,
                            description = feature.description,
                            enabled = aiFeatureEnabled(aiState, feature),
                            onChange = { aiSettings.setFeature(feature, it) },
                        )
                    }
            }

            // Daily digest config — only when the platform supplied
            // a concrete settings impl (Android does today; desktop
            // gets null until a scheduler lands).
            if (dailyDigestSettings != null) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                DailyDigestSection(
                    settings = dailyDigestSettings,
                    onTestDigest = onTestDigest,
                )
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DailyDigestSection(
    settings: io.nisfeb.talon.ai.DailyDigestSettings,
    onTestDigest: (() -> Unit)?,
) {
    val ddState by settings.state.collectAsState()
    var showTimePicker by remember { mutableStateOf(false) }

    Text(
        "Daily digest",
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    )
    Text(
        "A morning brief at your chosen time: unread, watchword hits, and @mentions. " +
            "The AI summary toggle is in Cloud features above; this section just controls the alarm.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Enabled", style = MaterialTheme.typography.bodyLarge)
            val sub = if (ddState.enabled) {
                "Next: ${formatNextFire(ddState.hourOfDay, ddState.minuteOfDay)}"
            } else "Off"
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = ddState.enabled,
            onCheckedChange = { settings.setEnabled(it) },
        )
    }

    if (ddState.enabled) {
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

        // "Test now" only when the platform supplies an immediate-
        // fire path (Android wires DailyDigest.generateAndNotifyAsync;
        // desktop has no equivalent yet).
        if (onTestDigest != null) {
            OutlinedButton(onClick = onTestDigest) { Text("Test now") }
        }
    }

    if (showTimePicker) {
        val state = androidx.compose.material3.rememberTimePickerState(
            initialHour = ddState.hourOfDay,
            initialMinute = ddState.minuteOfDay,
            is24Hour = false,
        )
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    settings.setTime(state.hour, state.minute)
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            },
            text = {
                androidx.compose.material3.TimePicker(state = state)
            },
        )
    }
}

/** Wallclock-friendly "Next: 7:30 AM" / "Tomorrow at 7:30 AM" string. */
private fun formatNextFire(hourOfDay: Int, minuteOfDay: Int): String {
    val now = java.util.Calendar.getInstance()
    val target = (now.clone() as java.util.Calendar).apply {
        set(java.util.Calendar.HOUR_OF_DAY, hourOfDay)
        set(java.util.Calendar.MINUTE, minuteOfDay)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    val tomorrow = target.timeInMillis <= now.timeInMillis
    if (tomorrow) target.add(java.util.Calendar.DAY_OF_MONTH, 1)
    val fmt = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
    val timeStr = fmt.format(target.time)
    return if (tomorrow) "Tomorrow at $timeStr" else "Today at $timeStr"
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
private fun AccentSwatchRow(
    label: String,
    color: androidx.compose.ui.graphics.Color?,
    fallbackHint: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(color ?: MaterialTheme.colorScheme.surfaceVariant),
        )
        Text(
            text = if (color != null) label else fallbackHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun ThemePreference.Mode.label(): String = when (this) {
    ThemePreference.Mode.System -> "System"
    ThemePreference.Mode.Light -> "Light"
    ThemePreference.Mode.Dark -> "Dark"
}
