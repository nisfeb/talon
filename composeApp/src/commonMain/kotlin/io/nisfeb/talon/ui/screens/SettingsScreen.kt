package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
    /** Process-wide notification health diagnostics. Optional —
     *  call sites that pre-date the panel pass null and the section
     *  doesn't render. */
    notificationHealth: io.nisfeb.talon.notify.NotificationHealth? = null,
    /** OS-level signals the panel surfaces alongside the relay /
     *  SSE diagnostics. Defaults to NoopSystemNotificationProbe so
     *  desktop / tests get a "n/a" rendering rather than missing
     *  rows. */
    systemNotificationProbe: io.nisfeb.talon.notify.SystemNotificationProbe =
        io.nisfeb.talon.notify.NoopSystemNotificationProbe,
    /** Optional relay-registration controls. When non-null, the
     *  Notification Health panel grows a Push relay sub-panel that
     *  lets the user register / unregister their device with a
     *  notification relay. null hides the sub-panel — used by tests
     *  and any host that hasn't wired RelayClient + RelaySettings
     *  + PushTokenProvider. */
    relayConfig: RelayPanelConfig? = null,
    onBack: () -> Unit,
    /** Optional daily-digest config + alarm controls. Android wires
     *  the JSON-prefs-backed impl that drives AlarmManager; desktop
     *  passes null until a desktop scheduler lands and the section
     *  hides entirely. */
    dailyDigestSettings: io.nisfeb.talon.ai.DailyDigestSettings? = null,
    /** Optional Android-only "Test now" handler that fires the digest
     *  immediately. When null the button isn't rendered. */
    onTestDigest: (() -> Unit)? = null,
    /** Opens the dedicated Sidebar visibility screen — lets the user
     *  toggle which rail items show. Defaults to no-op for callers
     *  that haven't wired the sub-screen yet. */
    onOpenSidebarSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val aiState by aiSettings.state.collectAsState()
    val themeMode by themePreference.mode.collectAsState()
    val hideComposerButtons by uiSettings.hideComposerButtons.collectAsState()
    val powerFeaturesEnabled by uiSettings.powerFeaturesEnabled.collectAsState()
    val accentSettings by uiSettings.accentSettings.collectAsState()
    val groupChannelOrder by uiSettings.groupChannelOrder.collectAsState()
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
                "Home list",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                "How channels sort under each group on the home list.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = groupChannelOrder == io.nisfeb.talon.ui.GroupChannelOrder.Recent,
                    onClick = {
                        uiSettings.setGroupChannelOrder(
                            io.nisfeb.talon.ui.GroupChannelOrder.Recent,
                        )
                    },
                    label = { Text("Most recent") },
                )
                FilterChip(
                    selected = groupChannelOrder == io.nisfeb.talon.ui.GroupChannelOrder.HostOrder,
                    onClick = {
                        uiSettings.setGroupChannelOrder(
                            io.nisfeb.talon.ui.GroupChannelOrder.HostOrder,
                        )
                    },
                    label = { Text("Host order") },
                )
            }
            Spacer(Modifier.height(8.dp))

            // ── Sidebar visibility ─────────────────────────────────
            // Drills into SidebarSettingsScreen where the user toggles
            // which rail items show. Inline here so it sits next to
            // the other home/rail personalisation rows.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSidebarSettings)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Sidebar", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Choose what shows in the rail.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))

            if (notificationHealth != null) {
                NotificationHealthPanel(
                    health = notificationHealth,
                    probe = systemNotificationProbe,
                )
                Spacer(Modifier.height(8.dp))
            }

            if (relayConfig != null) {
                RelayRegistrationPanel(relayConfig)
                Spacer(Modifier.height(8.dp))
            }

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

            FeatureToggleRow(
                label = "Power features",
                description = "Unlocks `/poke <app> <mark> <json>` from any composer — " +
                    "send arbitrary pokes to agents on your ship. Per-device opt-in; " +
                    "leaving this on multiple devices grants the same surface on each.",
                enabled = powerFeaturesEnabled,
                onChange = { uiSettings.setPowerFeaturesEnabled(it) },
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

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            AboutSection()
        }
    }
}

@Composable
private fun AboutSection() {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Text(
        "About",
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    )
    Text(
        "Talon — Compose Multiplatform Urbit chat client.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    AboutRow(
        label = "Version",
        value = "${io.nisfeb.talon.TalonBuild.versionName} " +
            "(build ${io.nisfeb.talon.TalonBuild.versionCode})",
    )
    AboutRow(
        label = "Platform",
        value = io.nisfeb.talon.ui.platformLabel,
    )
    AboutRow(
        label = "Source",
        value = "github.com/nisfeb/talon",
    )

    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = {
            clipboard.setText(
                androidx.compose.ui.text.AnnotatedString(
                    "Talon ${io.nisfeb.talon.TalonBuild.versionName} " +
                        "(build ${io.nisfeb.talon.TalonBuild.versionCode}) " +
                        "· ${io.nisfeb.talon.ui.platformLabel}",
                ),
            )
        },
    ) { Text("Copy version info") }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
        )
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

@Composable
private fun NotificationHealthPanel(
    health: io.nisfeb.talon.notify.NotificationHealth,
    probe: io.nisfeb.talon.notify.SystemNotificationProbe,
) {
    val sseConnected by health.sseConnected.collectAsState()
    val lastSseEvent by health.lastSseEventMs.collectAsState()
    val lastReconcile by health.lastReconcileMs.collectAsState()
    val forceReconnects by health.forceReconnects.collectAsState()
    val recoveredEvents by health.recoveredEvents.collectAsState()
    // Re-snapshot the OS state on every recomposition. Cheap (three
    // syscalls); the user opening Settings is a deliberate "tell me
    // what's wrong" moment, so a stale read here would defeat the
    // panel's purpose.
    val systemState = probe.snapshot()

    Text(
        "Notification health",
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    )
    Text(
        "If something below looks wrong, real-time delivery may be " +
            "degraded — restart Talon, check your network, or look " +
            "at battery / background-app permissions.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        HealthRow(
            label = "Live channel",
            value = if (sseConnected) "connected" else "reconnecting…",
            highlight = !sseConnected,
        )
        HealthRow(
            label = "Last event",
            value = formatHealthAge(lastSseEvent),
        )
        HealthRow(
            label = "Last sync",
            value = formatHealthAge(lastReconcile),
        )
        if (forceReconnects > 0) {
            HealthRow(
                label = "Force-reconnects (this session)",
                value = forceReconnects.toString(),
                highlight = forceReconnects >= 3,
            )
        }
        if (recoveredEvents > 0) {
            HealthRow(
                label = "Events recovered by sync",
                value = recoveredEvents.toString(),
            )
        }
        // OS-level signals — only render rows we actually got a
        // value for. Each highlights when the value would silently
        // drop notifications.
        systemState.notificationsAllowed?.let { allowed ->
            HealthRow(
                label = "OS notifications",
                value = if (allowed) "allowed" else "blocked — fix in Settings",
                highlight = !allowed,
            )
        }
        systemState.batteryOptimizationsExempt?.let { exempt ->
            HealthRow(
                label = "Battery optimization",
                value = if (exempt) "exempt" else "throttled — fix in Settings",
                highlight = !exempt,
            )
        }
        systemState.backgroundRestricted?.let { restricted ->
            HealthRow(
                label = "Background activity",
                value = if (restricted) "RESTRICTED — fix in Settings"
                    else "allowed",
                highlight = restricted,
            )
        }
    }
    // Fix-it buttons. Render only when there's something to fix
    // AND the probe knows how to deeplink there.
    val needsBatteryFix = systemState.batteryOptimizationsExempt == false
    val needsAppDetails = systemState.notificationsAllowed == false ||
        systemState.backgroundRestricted == true
    if (needsBatteryFix || needsAppDetails) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            if (needsBatteryFix) {
                TextButton(onClick = { probe.openBatteryOptimizationSettings() }) {
                    Text("Fix battery")
                }
            }
            if (needsAppDetails) {
                TextButton(onClick = { probe.openAppDetailsSettings() }) {
                    Text("App settings")
                }
            }
        }
    }
}

@Composable
private fun HealthRow(
    label: String,
    value: String,
    highlight: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = if (highlight) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun formatHealthAge(ms: Long): String {
    if (ms <= 0L) return "never"
    val now = System.currentTimeMillis()
    val ageMs = (now - ms).coerceAtLeast(0L)
    return when {
        ageMs < 60_000L -> "${ageMs / 1000}s ago"
        ageMs < 3_600_000L -> "${ageMs / 60_000L}m ago"
        ageMs < 24L * 3_600_000L -> "${ageMs / 3_600_000L}h ago"
        else -> "${ageMs / (24L * 3_600_000L)}d ago"
    }
}

/**
 * Plumbing the SettingsScreen needs to render the relay sub-panel.
 * Hosts construct one of these from their app-graph singletons; the
 * settings UI never reaches into the singletons directly so the
 * panel stays testable from a fixture.
 *
 * `activePatp` + `activeShipUrl` come from the host's ship state —
 * the relay registers `(device, ship)` pairs, so the panel needs
 * to know which ship "this device" is registering on right now.
 */
data class RelayPanelConfig(
    val client: io.nisfeb.talon.notify.RelayClient,
    val settings: io.nisfeb.talon.notify.RelaySettings,
    val pushTokens: io.nisfeb.talon.notify.PushTokenProvider,
    val activePatp: String?,
    val activeShipUrl: String?,
)

@Composable
private fun RelayRegistrationPanel(config: RelayPanelConfig) {
    val scope = rememberCoroutineScope()
    val endpoint by config.settings.endpoint.collectAsState()
    var endpointDraft by remember(endpoint) { mutableStateOf(endpoint) }

    val deviceId = config.activePatp?.let { config.settings.deviceIdFor(it) }.orEmpty()
    var working by remember(config.activePatp) { mutableStateOf(false) }
    var status by remember(config.activePatp, deviceId) {
        mutableStateOf(
            if (deviceId.isNotBlank()) "Registered (deviceId=${deviceId.take(8)}…)"
            else "Not registered with the relay yet.",
        )
    }
    var codePrompt by remember(config.activePatp) { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }

    Text(
        "Push relay",
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    )
    Text(
        "Optional: register this device with a notification relay so " +
            "pushes still arrive when Talon is killed by Android or " +
            "force-stopped. The default endpoint is the Talon-operated " +
            "host; self-host by pointing at your own.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedTextField(
        value = endpointDraft,
        onValueChange = { endpointDraft = it },
        label = { Text("Endpoint") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            enabled = endpointDraft.isNotBlank() && endpointDraft != endpoint,
            onClick = { config.settings.setEndpoint(endpointDraft.trim()) },
        ) { Text("Save endpoint") }
    }

    Text(
        status,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    var diagnoseReport by remember {
        mutableStateOf<io.nisfeb.talon.notify.DistributorReport?>(null)
    }
    TextButton(
        enabled = !working,
        onClick = {
            scope.launch {
                diagnoseReport = config.pushTokens.diagnose()
            }
        },
    ) { Text("Diagnose distributor") }

    diagnoseReport?.let { report ->
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "PackageManager.queryBroadcastReceivers (${report.byPackageManager.size}):",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            if (report.byPackageManager.isEmpty()) {
                Text(
                    "  (none — visibility / queries mismatch)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                report.byPackageManager.forEach {
                    Text(
                        "  $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                "UnifiedPush.getDistributors (${report.byConnector.size}):",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            if (report.byConnector.isEmpty()) {
                Text(
                    "  (none)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                report.byConnector.forEach {
                    Text(
                        "  $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                "Cached endpoint: ${report.cachedEndpoint ?: "(none)"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (report.note.isNotBlank()) {
                Text(
                    report.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    val ship = config.activePatp
    val shipUrl = config.activeShipUrl
    if (ship == null || shipUrl == null) {
        Text(
            "Sign in to a ship to register with the relay.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (deviceId.isBlank()) {
            TextButton(
                enabled = !working,
                onClick = { codePrompt = true; code = "" },
            ) { Text("Register this device") }
        } else {
            TextButton(
                enabled = !working,
                onClick = { codePrompt = true; code = "" },
            ) { Text("Re-register (rotate token)") }
            TextButton(
                enabled = !working,
                onClick = {
                    working = true
                    status = "Unregistering…"
                    scope.launch {
                        val ok = config.client.unregister(deviceId)
                        if (ok) {
                            config.settings.clearDeviceIdFor(ship)
                            status = "Unregistered."
                        } else {
                            status = "Unregister failed; check your endpoint."
                        }
                        working = false
                    }
                },
            ) {
                Text(
                    "Unregister",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (codePrompt) {
        AlertDialog(
            onDismissRequest = { if (!working) codePrompt = false },
            title = { Text("Register with relay") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Paste this ship's +code. The relay logs in once " +
                            "to derive a session cookie, then forgets the " +
                            "+code. See the design doc for full security " +
                            "details.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("+code") },
                        singleLine = true,
                        visualTransformation =
                            androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !working && code.isNotBlank(),
                    onClick = {
                        working = true
                        status = "Registering…"
                        val codeSnapshot = code
                        codePrompt = false
                        code = ""
                        scope.launch {
                            val endpoint = config.pushTokens.token()
                            if (endpoint == null) {
                                // token() suspends up to 10s waiting for the
                                // distributor's NEW_ENDPOINT broadcast. A null
                                // return at this point means either no
                                // distributor is installed, or the one we
                                // chose didn't respond — both call for user
                                // action, but the message should distinguish.
                                val report = config.pushTokens.diagnose()
                                status = if (report.byConnector.isEmpty()) {
                                    "No UnifiedPush distributor found. " +
                                        "Install ntfy, NextPush, or another " +
                                        "distributor app, then try again."
                                } else {
                                    "${report.byConnector.first()} didn't " +
                                        "deliver an endpoint within 10s. " +
                                        "Open it once to wake it up, then " +
                                        "try again."
                                }
                                working = false
                                return@launch
                            }
                            val newId = config.client.register(
                                platform = config.pushTokens.platform,
                                pushEndpoint = endpoint,
                                existingDeviceId = config.settings.deviceIdFor(ship),
                                shipUrl = shipUrl,
                                patp = ship,
                                code = codeSnapshot,
                            )
                            if (newId != null) {
                                config.settings.setDeviceIdFor(ship, newId)
                                status = "Registered (deviceId=${newId.take(8)}…)"
                            } else {
                                status = "Registration failed. Check the endpoint, " +
                                    "your +code, and that the ship is reachable from " +
                                    "the relay."
                            }
                            working = false
                        }
                    },
                ) { Text("Register") }
            },
            dismissButton = {
                TextButton(
                    enabled = !working,
                    onClick = { codePrompt = false; code = "" },
                ) { Text("Cancel") }
            },
        )
    }
}

private fun ThemePreference.Mode.label(): String = when (this) {
    ThemePreference.Mode.System -> "System"
    ThemePreference.Mode.Light -> "Light"
    ThemePreference.Mode.Dark -> "Dark"
}
