package io.nisfeb.talon.ui.screens
import kotlinx.datetime.toLocalDateTime
import io.nisfeb.talon.util.nowMs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.Surface
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.VerticalDivider
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.horizontalScroll
import io.nisfeb.talon.ui.theme.hex
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
import io.nisfeb.talon.ai.AgentPrompt
import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.ai.AiSettingsRepository
import io.nisfeb.talon.ai.LoopPrompt
import io.nisfeb.talon.ui.UiSettings
import io.nisfeb.talon.ui.isOnDeviceAiFeatureSupported
import io.nisfeb.talon.ui.isAssistantSupported
import io.nisfeb.talon.ui.isBackgroundCallRingSupported
import io.nisfeb.talon.ui.isCallsSupported
import io.nisfeb.talon.ui.isLoopsSupported
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
    /**
     * People the status widget can be told to pin, as ship to display
     * name. Empty where the host has no contacts wired, which hides
     * the pinning control rather than showing an empty picker.
     */
    homePinCandidates: List<Pair<String, String>> = emptyList(),
    onBack: () -> Unit,
    /** Optional call controller. When non-null (and the platform does
     *  calls at all) Settings grows a "Who can call you" section that
     *  edits the ship-level policy %trunk enforces. null hides it —
     *  tests and hosts that haven't wired calls. */
    callController: io.nisfeb.talon.call.CallController? = null,
    /** Optional daily-digest config + alarm controls. Android wires
     *  the JSON-prefs-backed impl that drives AlarmManager; desktop
     *  passes null until a desktop scheduler lands and the section
     *  hides entirely. */
    /** Optional Android-only "Test now" handler that fires the digest
     *  immediately. When null the button isn't rendered. */
    onTestDigest: (() -> Unit)? = null,
    /** Opens the dedicated Sidebar visibility screen — lets the user
     *  toggle which rail items show. Defaults to no-op for callers
     *  that haven't wired the sub-screen yet. */
    onOpenSidebarSettings: () -> Unit = {},
    /** Opens the login-handoff QR generator. Defaults to no-op so
     *  hosts that haven't wired the share screen yet (tests, older
     *  call sites) don't render the row. */
    onOpenShareLoginQr: () -> Unit = {},
    /** Opens the Loops screen (scheduled agent prompts). Defaults to
     *  no-op; the row only renders where isLoopsSupported + a key. */
    onOpenLoops: () -> Unit = {},
    /** The comet Talon runs on this computer; Noop where unsupported. */
    localShip: io.nisfeb.talon.comet.LocalShip = io.nisfeb.talon.comet.LocalShip.Noop,
    /** Open on the Account tab, where the local ship and its dojo live. */
    startOnAccount: Boolean = false,
    onAlwaysPatpChanged: (Boolean) -> Unit = {},
    /** Fired after the word-names toggle flips; hosts push the new
     *  value to %settings so the choice follows the user. Local apply
     *  and persist happen regardless. */
    onNonCometNamesChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val aiState by aiSettings.state.collectAsState()
    val themeMode by themePreference.mode.collectAsState()
    val hideComposerButtons by uiSettings.hideComposerButtons.collectAsState()
    val powerFeaturesEnabled by uiSettings.powerFeaturesEnabled.collectAsState()
    val density by uiSettings.density.collectAsState()
    val homeFahrenheit by uiSettings.homeFahrenheit.collectAsState()
    val homeTwentyFourHour by uiSettings.homeTwentyFourHour.collectAsState()
    val homeLayoutRaw by uiSettings.homeLayout.collectAsState()
    val homeLayout = remember(homeLayoutRaw) {
        io.nisfeb.talon.ui.HomeLayoutCodec.decode(homeLayoutRaw)
    }
    fun saveHomeLayout(next: io.nisfeb.talon.ui.HomeLayout) {
        uiSettings.setHomeLayout(io.nisfeb.talon.ui.HomeLayoutCodec.encode(next))
    }
    val accentSettings by uiSettings.accentSettings.collectAsState()
    val groupChannelOrder by uiSettings.groupChannelOrder.collectAsState()
    val folderItemOrder by uiSettings.folderItemOrder.collectAsState()
    val accentEnabled = io.nisfeb.talon.ui.AccentSettings
        .isEnabled(accentSettings, multiShip)
    var customHexInput by remember(accentSettings.customHex) {
        mutableStateOf(accentSettings.customHex.orEmpty())
    }

    // Keyed to the fields these mirror — NOT the whole aiState — so a
    // repo write refreshes the editor (a remote %settings sync landing,
    // or a local Save) without a same-screen toggle or prompt edit
    // wiping in-progress credential edits: Config also carries every
    // feature switch, and keying on all of it re-bunted these fields
    // whenever any switch flipped. Accepted edge: an unsaved edit is
    // replaced when a remote sync changes that same field mid-edit.
    var provider by remember(aiState.provider) { mutableStateOf(aiState.provider) }
    var apiKey by remember(aiState.apiKey) { mutableStateOf(aiState.apiKey) }
    var model by remember(aiState.model) { mutableStateOf(aiState.model.orEmpty()) }
    var baseUrl by remember(aiState.baseUrl) { mutableStateOf(aiState.baseUrl.orEmpty()) }
    var revealKey by remember { mutableStateOf(false) }
    var providerMenuOpen by remember { mutableStateOf(false) }
    var braveKey by remember(aiState.braveApiKey) { mutableStateOf(aiState.braveApiKey) }
    var revealBrave by remember { mutableStateOf(false) }
    var sttKey by remember(aiState.sttApiKey) { mutableStateOf(aiState.sttApiKey) }
    var revealStt by remember { mutableStateOf(false) }
    var promptEditorKind by remember { mutableStateOf<AiSettings.PromptKind?>(null) }

    // Compare on the same normalization Save persists (trim + blank→null)
    // so e.g. a pasted key's trailing newline doesn't leave Save enabled
    // forever after a successful save.
    val dirty = provider != aiState.provider ||
        apiKey.trim() != aiState.apiKey ||
        (model.trim().ifBlank { null } != aiState.model) ||
        (baseUrl.trim().ifBlank { null } != aiState.baseUrl)

    Column(modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            io.nisfeb.talon.ui.NavIcon(onBack = onBack)
            Text(
                "Settings",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        HorizontalDivider()

        val visibleTabs = buildList {
            add(SettingsTab.Appearance)
            add(SettingsTab.Home)
            add(SettingsTab.Chats)
            if (notificationHealth != null || relayConfig != null ||
                relayConfig != null) {
                add(SettingsTab.Notifications)
            }
            add(SettingsTab.Ai)
            if (isCallsSupported && callController != null) add(SettingsTab.Calls)
            add(SettingsTab.Account)
            add(SettingsTab.About)
        }
        var tab by remember {
            mutableStateOf(if (startOnAccount) SettingsTab.Account else SettingsTab.Appearance)
        }
        val safeTab = if (tab in visibleTabs) tab else visibleTabs.first()

        @Composable
        fun Body(bodyModifier: Modifier) {
            Column(
                modifier = bodyModifier
                    .widthIn(max = 760.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            if (safeTab == SettingsTab.Appearance) {
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

            // ── Density ─────────────────────────────────────────────
            // ── Custom themes ───────────────────────────────────────
            Spacer(Modifier.height(8.dp))
            val themeSettings by uiSettings.themeSettings.collectAsState()
            var themeDraft by remember {
                mutableStateOf<io.nisfeb.talon.ui.theme.CustomTheme?>(null)
            }
            val darkNow = MaterialTheme.colorScheme.background.luminance() < 0.5f
            Text("Custom themes", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Pick your own colors. Saved themes sync to your ship with " +
                    "your other settings, so they follow you to every device.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                FilterChip(
                    selected = themeSettings.activeId == null,
                    onClick = { uiSettings.setThemeSettings(themeSettings.copy(activeId = null)) },
                    label = { Text("Built-in") },
                )
                themeSettings.themes.forEach { t ->
                    FilterChip(
                        selected = themeSettings.activeId == t.id,
                        onClick = { uiSettings.setThemeSettings(themeSettings.copy(activeId = t.id)) },
                        label = { Text(t.name) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedButton(onClick = {
                    themeDraft = io.nisfeb.talon.ui.theme.CustomTheme.blank(
                        dark = darkNow,
                        id = kotlin.random.Random.nextLong().toString(36).trimStart('-'),
                    )
                }) { Text("New theme") }
                themeSettings.active?.let { t ->
                    androidx.compose.material3.OutlinedButton(onClick = { themeDraft = t }) { Text("Edit") }
                    TextButton(onClick = {
                        uiSettings.setThemeSettings(
                            themeSettings.copy(
                                themes = themeSettings.themes.filter { it.id != t.id },
                                activeId = null,
                            ),
                        )
                    }) { Text("Delete") }
                }
            }
            themeDraft?.let { d ->
                CustomThemeEditor(
                    draft = d,
                    onDraft = { themeDraft = it },
                    onCancel = { themeDraft = null },
                    onSave = {
                        val others = themeSettings.themes.filter { it.id != d.id }
                        uiSettings.setThemeSettings(themeSettings.copy(themes = others + d, activeId = d.id))
                        themeDraft = null
                    },
                )
            }
            Text("Density", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Tightens or loosens chat-list rows, message spacing, and " +
                    "bubble padding. Per-device.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                io.nisfeb.talon.ui.Density.entries.forEach { mode ->
                    FilterChip(
                        selected = density == mode,
                        onClick = { uiSettings.setDensity(mode) },
                        label = { Text(mode.name) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            // ── Ship naming ─────────────────────────────────────────
            val alwaysPatp by io.nisfeb.talon.ui.ShipNames.alwaysPatp.collectAsState()
            FeatureToggleRow(
                label = "Always show ~ship names",
                description = "Ignore nicknames and word-based names " +
                    "everywhere — rows, mentions and quoted posts all " +
                    "show the raw Urbit name.",
                enabled = alwaysPatp,
                onChange = { on ->
                    io.nisfeb.talon.ui.ShipNames.setAlwaysPatp(on)
                    onAlwaysPatpChanged(on)
                },
            )
            val nonCometNames by io.nisfeb.talon.ui.AzimuthNames.enabled.collectAsState()
            FeatureToggleRow(
                label = "Word names for planets and moons",
                description = if (alwaysPatp) {
                    "Turned off while \"Always show ~ship names\" is on."
                } else {
                    "Comets always show word names -- their Urbit name " +
                        "is their key. A planet's is not, so its words " +
                        "have to be looked up from its keys, and only " +
                        "on a ship that can do the lookup. Off shows " +
                        "the raw ~ship for everything but comets."
                },
                enabled = nonCometNames && !alwaysPatp,
                onChange = { on ->
                    io.nisfeb.talon.ui.AzimuthNames.setEnabled(on)
                    onNonCometNamesChanged(on)
                },
                switchEnabled = !alwaysPatp,
            )
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

            Text(
                "How groups and folder contents sort at the top level. " +
                    "Unread items still float to the top in either mode.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = folderItemOrder == io.nisfeb.talon.ui.FolderItemOrder.Manual,
                    onClick = {
                        uiSettings.setFolderItemOrder(
                            io.nisfeb.talon.ui.FolderItemOrder.Manual,
                        )
                    },
                    label = { Text("Saved order") },
                )
                FilterChip(
                    selected = folderItemOrder == io.nisfeb.talon.ui.FolderItemOrder.Recent,
                    onClick = {
                        uiSettings.setFolderItemOrder(
                            io.nisfeb.talon.ui.FolderItemOrder.Recent,
                        )
                    },
                    label = { Text("Most recent") },
                )
            }
            Spacer(Modifier.height(8.dp))

            }
            if (safeTab == SettingsTab.Home) {
            Text(
                "Home",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            // One group so far. The home page has four panels and only
            // the dial has anything to set yet, so this is laid out as a
            // list of groups rather than a flat run of controls — the
            // next thing added should be another heading, not a chip
            // dropped in beside these.
            Text(
                "Clock and weather",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "How the dial reads out temperature and the hour. Kept on this " +
                    "device rather than on the ship: which units somebody reads " +
                    "is a fact about them, not about their identity.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = homeFahrenheit,
                    onClick = { uiSettings.setHomeFahrenheit(true) },
                    label = { Text("Fahrenheit") },
                )
                FilterChip(
                    selected = !homeFahrenheit,
                    onClick = { uiSettings.setHomeFahrenheit(false) },
                    label = { Text("Celsius") },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !homeTwentyFourHour,
                    onClick = { uiSettings.setHomeTwentyFourHour(false) },
                    label = { Text("12-hour") },
                )
                FilterChip(
                    selected = homeTwentyFourHour,
                    onClick = { uiSettings.setHomeTwentyFourHour(true) },
                    label = { Text("24-hour") },
                )
            }
            Text(
                "The place the dial uses is set on the dial itself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            Text(
                "Widgets",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                "What the home page carries. Order and size are set on the " +
                    "page itself, under Arrange.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            homeLayout.widgets.forEach { widget ->
                HomeWidgetRow(
                    widget = widget,
                    pinCandidates = homePinCandidates,
                    onChange = { saveHomeLayout(homeLayout.with(it)) },
                )
            }
            }
            if (safeTab == SettingsTab.Chats) {
            // ── Which sections show, and in what order ─────────────
            //
            // Drills into SidebarSettingsScreen. The same preferences
            // drive the desktop rail and the mobile drawer, so this row
            // is no longer hidden on a phone: it used to be, back when
            // the rail was the only thing they drove and a phone had no
            // rail to change.
            val drawerNav = io.nisfeb.talon.ui.isDrawerNavigation
            val wide = drawerNav || with(androidx.compose.ui.platform.LocalDensity.current) {
                androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toDp()
            } >= io.nisfeb.talon.ui.ExpandedThreshold
            if (wide) Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSidebarSettings)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (drawerNav) "Menu" else "Sidebar",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        // Named for the thing in front of them rather
                        // than for the one this setting was built for.
                        if (drawerNav) "Choose what shows in the menu, and in what order."
                        else "Choose what shows in the rail.",
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
            if (wide) Spacer(Modifier.height(4.dp))

            }
            if (safeTab == SettingsTab.Account) {
            // ── Login QR generator ─────────────────────────────────
            // Lets the user build a `talon://login?...` QR for someone
            // else (assisted onboarding flow). Generation works on
            // every platform; scanning lives on Android via ZXing.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenShareLoginQr)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Login QR generator", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Build a scannable QR with a ship URL + +code for handoff.",
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

            if (io.nisfeb.talon.ui.isLocalCometSupported && localShip.pierExists()) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                LocalShipSection(localShip)
            }
            }
            if (safeTab == SettingsTab.Notifications) {
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

            }
            if (safeTab == SettingsTab.Chats) {
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

            }
            if (safeTab == SettingsTab.Ai) {
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
                        // The chat credential only. clear() also wiped
                        // the Whisper key, the Brave key, every custom
                        // system prompt and every feature toggle — none
                        // of which this button names.
                        aiSettings.update(AiSettings.Provider.Anthropic, "", null, null)
                        provider = AiSettings.Provider.Anthropic
                        apiKey = ""
                        model = ""
                        baseUrl = ""
                    },
                    enabled = aiState.hasKey(),
                ) { Text("Remove key") }
            }
            // Any stored credential is enough to want this switch: it
            // used to live under hasKey(), so someone who set only a
            // Whisper key had it pushed to %settings (syncEnabled
            // defaults on) with no way to opt out short of pasting a
            // chat key first.
            if (aiState.hasKey() || aiState.sttApiKey.isNotBlank() ||
                aiState.braveApiKey.isNotBlank()
            ) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                FeatureToggleRow(
                    label = "Sync AI settings across devices",
                    description = "Stores your provider, model, toggles, and all three " +
                        "API keys — chat, Whisper transcription, and Brave Search — in " +
                        "%settings on the ship. The keys will be on the ship — only " +
                        "enable if you trust the ship.",
                    enabled = aiState.syncEnabled,
                    onChange = { aiSettings.setSyncEnabled(it) },
                )
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
                Text(
                    "Cloud features",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
                AiSettings.Feature.values()
                    .filter { it.requiresCloudKey }
                    // The Assistant needs the embedder host (isAssistantSupported);
                    // the other cloud features run anywhere a key is set.
                    .filter { isAssistantSupported || it != AiSettings.Feature.Agent }
                    .forEach { feature ->
                        FeatureToggleRow(
                            label = feature.label,
                            description = feature.description,
                            enabled = aiFeatureEnabled(aiState, feature),
                            onChange = { aiSettings.setFeature(feature, it) },
                        )
                    }

                // The assistant subsumes MCP (ship tools) and web access —
                // no separate toggles. When it's on, offer the optional
                // Brave key that powers its web search (it can open URLs
                // without one).
                if (isAssistantSupported && aiFeatureEnabled(aiState, AiSettings.Feature.Agent)) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Assistant web search",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    )
                    OutlinedTextField(
                        value = braveKey,
                        onValueChange = { braveKey = it },
                        label = { Text("Brave Search API key (optional)") },
                        singleLine = true,
                        visualTransformation = if (revealBrave) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { revealBrave = !revealBrave }) {
                                Icon(
                                    imageVector = if (revealBrave) Icons.Filled.VisibilityOff
                                    else Icons.Filled.Visibility,
                                    contentDescription = if (revealBrave) "Hide key" else "Show key",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { aiSettings.setBraveApiKey(braveKey.trim()) },
                            enabled = braveKey.trim() != aiState.braveApiKey,
                        ) { Text("Save key") }
                    }
                    Text(
                        "Optional — a Brave Search API key lets the assistant search " +
                            "the web (it can already open URLs without one). " +
                            "Get a free key at search.brave.com/help/api.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(8.dp))
                    Text(
                        "System prompts",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Text(
                        "The instructions the AI follows. Urbit knowledge is shared by " +
                            "the assistant and scheduled jobs; each also has its own. " +
                            "Customizing changes behavior; edits sync across your devices.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PROMPT_PARTS.forEach { part ->
                        OutlinedButton(onClick = { promptEditorKind = part.kind }) {
                            Text(
                                if (aiState.prompt(part.kind).isBlank()) "Edit ${part.label}"
                                else "Edit ${part.label} (customized)",
                            )
                        }
                    }
                    promptEditorKind?.let { kind ->
                        val part = PROMPT_PARTS.first { it.kind == kind }
                        SystemPromptEditorDialog(
                            title = part.label,
                            current = aiState.prompt(kind),
                            default = part.default,
                            onSave = {
                                aiSettings.setPrompt(kind, it)
                                promptEditorKind = null
                            },
                            onDismiss = { promptEditorKind = null },
                        )
                    }
                }
            }

            // ── Call transcription (Whisper) ───────────────────────
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                "Call transcription",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                "Transcribing a recorded party line uses OpenAI Whisper. If your " +
                    "chat provider above is OpenAI (or a compatible Custom endpoint) " +
                    "that key is used automatically \u2014 otherwise paste a " +
                    "Whisper-capable key here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = sttKey,
                onValueChange = { sttKey = it },
                label = { Text("Whisper (OpenAI) API key") },
                singleLine = true,
                visualTransformation = if (revealStt) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { revealStt = !revealStt }) {
                        Icon(
                            imageVector = if (revealStt) Icons.Filled.VisibilityOff
                            else Icons.Filled.Visibility,
                            contentDescription = if (revealStt) "Hide key" else "Show key",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { aiSettings.setSttApiKey(sttKey.trim()) },
                    enabled = sttKey.trim() != aiState.sttApiKey,
                ) { Text("Save key") }
                if (aiState.sttApiKey.isNotBlank()) {
                    TextButton(onClick = {
                        aiSettings.setSttApiKey("")
                        sttKey = ""
                    }) { Text("Remove") }
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

            }
            if (safeTab == SettingsTab.Notifications) {
            }
            if (safeTab == SettingsTab.Calls) {
            // Who may ring this ship. The policy lives in %trunk, not
            // here — this is only its editor, and any other app on the
            // ship sees the same lists.
            if (isCallsSupported && callController != null) {
                CallPolicySection(callController)
                CallServersSection(callController)
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    "Microphone processing",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    "What the app does to your microphone before sending it. " +
                        "One size does not fit all: a headset in a quiet room wants " +
                        "less of this than a laptop mic in a café. Per device; takes " +
                        "effect on the next call or line you join.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val mic by uiSettings.micProcessing.collectAsState()
                FeatureToggleRow(
                    label = "Noise suppression",
                    description = "Removes fans, keyboards and room hum. Also eats music " +
                        "and some voices; turn it off if people say you sound underwater.",
                    enabled = mic.noiseSuppression,
                    onChange = { uiSettings.setMicProcessing(mic.copy(noiseSuppression = it)) },
                )
                FeatureToggleRow(
                    label = "Echo cancellation",
                    description = "Stops the room from hearing itself when you use speakers. " +
                        "Safe to turn off with a headset.",
                    enabled = mic.echoCancellation,
                    onChange = { uiSettings.setMicProcessing(mic.copy(echoCancellation = it)) },
                )
                FeatureToggleRow(
                    label = "Auto gain",
                    description = "Evens out your level so whispers and shouts arrive alike. " +
                        "Turn it off for a mic you have already set up, or for music.",
                    enabled = mic.autoGainControl,
                    onChange = { uiSettings.setMicProcessing(mic.copy(autoGainControl = it)) },
                )
                // No background ring on this platform — say so plainly
                // rather than letting a missed call be the discovery.
                if (!isBackgroundCallRingSupported) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Incoming calls ring only while Talon is open.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            }
            if (safeTab == SettingsTab.Ai) {
            // Loops — scheduled agent prompts. Needs a cloud key (it runs
            // the agent) and a platform that can fire it, so it's gated on
            // isLoopsSupported (Android via AlarmManager; desktop via the
            // while-open ticker — both true).
            if (isLoopsSupported && aiState.hasKey()) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenLoops)
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Loops", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Run a saved prompt over your chats on a schedule.",
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
            }

            }
            if (safeTab == SettingsTab.About) {
            // First section of its tab: no leading divider.
            Spacer(Modifier.height(8.dp))
            AboutSection(callController)
            }
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            if (maxWidth >= 720.dp) {
                Row(modifier = Modifier.fillMaxSize()) {
                    SettingsRail(visibleTabs, safeTab) { tab = it }
                    VerticalDivider()
                    Body(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    SettingsTabRow(visibleTabs, safeTab) { tab = it }
                    Body(Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun AboutSection(
    callController: io.nisfeb.talon.call.CallController? = null,
) {
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
    // The desk's wire version, beside the app's own. These two have to
    // agree for calls to work and they update by different routes —
    // the app from a release, the desk over ames from its publisher —
    // so "which one is behind" is a question people actually have to
    // answer, and the alternative was a dojo scry.
    val shipWire by (
        callController?.wire
            ?: kotlinx.coroutines.flow.MutableStateFlow(-1)
        ).collectAsState()
    if (callController != null) {
        AboutRow(
            label = "Calling (%trunk)",
            value = when {
                shipWire <= 0 -> "not installed on your ship"
                shipWire < io.nisfeb.talon.call.TrunkWire.WIRE_VERSION ->
                    "wire $shipWire · this app speaks " +
                        "${io.nisfeb.talon.call.TrunkWire.WIRE_VERSION}, so your " +
                        "ship's copy is behind"
                shipWire > io.nisfeb.talon.call.TrunkWire.WIRE_VERSION ->
                    "wire $shipWire · newer than this app, which speaks " +
                        "${io.nisfeb.talon.call.TrunkWire.WIRE_VERSION}"
                else -> "wire $shipWire"
            },
        )
    }

    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = {
            clipboard.setText(
                androidx.compose.ui.text.AnnotatedString(
                    // Includes the desk's wire: half the call reports
                    // so far have come down to a ship running an older
                    // %trunk than the app, and this is what someone
                    // pastes into a support thread.
                    "Talon ${io.nisfeb.talon.TalonBuild.versionName} " +
                        "(build ${io.nisfeb.talon.TalonBuild.versionCode}) " +
                        "· ${io.nisfeb.talon.ui.platformLabel}" +
                        if (callController != null) {
                            " · %trunk wire $shipWire " +
                                "(app speaks ${io.nisfeb.talon.call.TrunkWire.WIRE_VERSION})"
                        } else {
                            ""
                        },
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


/** Single source of truth for "is this feature toggle on?" — keeps the
 *  Settings UI's toggle state in lockstep with the gates wired across
 *  the rest of the app. */
internal fun aiFeatureEnabled(state: AiSettings.Config, feature: AiSettings.Feature): Boolean =
    when (feature) {
        AiSettings.Feature.CatchMeUp -> state.catchMeUpEnabled
        AiSettings.Feature.SmartFeatures -> state.smartFeaturesEnabled
        // Unified assistant: either legacy flag counts as enabled.
        AiSettings.Feature.Agent -> state.assistantOn()
    }

@Composable
private fun CallPolicySection(controller: io.nisfeb.talon.call.CallController) {
    // Null means we couldn't read the policy — no %trunk installed, or
    // a desk that predates it. Render nothing rather than an editor
    // whose every poke would be nacked.
    val policy by controller.policy.collectAsState()
    val current = policy ?: return
    val scope = rememberCoroutineScope()
    val allowOnly = current.mode == io.nisfeb.talon.call.CallPolicy.Mode.Allow

    // First section of the Calls tab: no leading divider.
    Spacer(Modifier.height(8.dp))
    Column {
        Text("Who can call you", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Your ship decides this, so it applies to every device you're " +
                "signed in on — and to any other app sharing your call setup.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        FeatureToggleRow(
            label = "Only people on my list",
            description = if (allowOnly) {
                "Everyone else's calls are ignored."
            } else {
                "Anyone can ring you, except people you've blocked."
            },
            enabled = allowOnly,
            onChange = { on ->
                scope.launch {
                    controller.setCallMode(
                        if (on) {
                            io.nisfeb.talon.call.CallPolicy.Mode.Allow
                        } else {
                            io.nisfeb.talon.call.CallPolicy.Mode.Open
                        },
                    )
                }
            },
        )
        if (allowOnly) {
            ShipListEditor(
                title = "Allowed",
                empty = "Nobody yet — with this on, no one can reach you.",
                ships = current.allow,
                onAdd = { scope.launch { controller.setAllowed(it, true) } },
                onRemove = { scope.launch { controller.setAllowed(it, false) } },
            )
        }
        ShipListEditor(
            title = "Blocked",
            empty = "Nobody blocked.",
            ships = current.block,
            onAdd = { scope.launch { controller.setBlocked(it, true) } },
            onRemove = { scope.launch { controller.setBlocked(it, false) } },
        )
    }
}

/**
 * One editable set of ships. Validation is deliberately loose — the
 * agent's own parser is the real gate, and it rejects a malformed @p
 * long before it reaches the policy.
 */
/**
 * The STUN/TURN servers 1:1 calls use.
 *
 * Party lines never appear here — Galène hands out its own on join.
 * A 1:1 has no server in the middle to ask, so this list is the only
 * thing standing between a call and "connection failed" whenever the
 * two people aren't on the same network.
 */
@Composable
private fun CallServersSection(controller: io.nisfeb.talon.call.CallController) {
    // Same reasoning as CallPolicySection: no readable policy means no
    // %trunk to poke, so an editor here would only nack.
    val policy by controller.policy.collectAsState()
    if (policy == null) return
    val servers by controller.ice.collectAsState()
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var cred by remember { mutableStateOf("") }
    val candidate = url.trim()
    val valid = candidate.startsWith("stun:") || candidate.startsWith("turn:")

    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(Modifier.height(8.dp))
    Column {
        Text("Call servers", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "How a one-to-one call finds a path between you when you're " +
                "on different networks. Stored on your ship, so it's the " +
                "same on every device. Party lines don't use these — they " +
                "get their own from the group's server.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        if (servers.isEmpty()) {
            // Not a cosmetic state: with no servers, every call off the
            // local network fails, and it used to do so silently.
            Text(
                "None set — calls will only work with people on your own " +
                    "network. Add a server below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            servers.forEach { s ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(s.url, style = MaterialTheme.typography.bodyMedium)
                        if (s.user.isNotEmpty()) {
                            Text(
                                "as ${s.user}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TextButton(
                        onClick = { scope.launch { controller.setIce(servers - s) } },
                    ) { Text("Remove") }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            singleLine = true,
            label = { Text("stun:… or turn:…") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                singleLine = true,
                label = { Text("User") },
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = cred,
                onValueChange = { cred = it },
                singleLine = true,
                label = { Text("Password") },
                modifier = Modifier.weight(1f),
            )
            Button(
                enabled = valid && servers.none { it.url == candidate },
                onClick = {
                    scope.launch {
                        controller.setIce(
                            servers + io.nisfeb.talon.call.IceServer(
                                candidate, user.trim(), cred.trim(),
                            ),
                        )
                    }
                    url = ""; user = ""; cred = ""
                },
            ) { Text("Add") }
        }
        Text(
            "A STUN server needs no user or password. Leave the list " +
                "empty to go back to the one this build ships with.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ShipListEditor(
    title: String,
    empty: String,
    ships: Set<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val candidate = draft.trim().let { if (it.startsWith("~")) it else "~$it" }
    val valid = candidate.length > 3 && candidate.drop(1).all { it.isLetter() || it == '-' }

    Spacer(Modifier.height(12.dp))
    Text(title, style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(4.dp))
    if (ships.isEmpty()) {
        Text(
            empty,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        ships.sorted().forEach { who ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(who, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { onRemove(who) }) { Text("Remove") }
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            label = { Text("~sampel-palnet") },
            modifier = Modifier.weight(1f),
        )
        Button(
            enabled = valid && candidate !in ships,
            onClick = { onAdd(candidate); draft = "" },
        ) { Text("Add") }
    }
}

@Composable
private fun FeatureToggleRow(
    label: String,
    description: String,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    // Whether the Switch is interactive ("enabled" is taken: it's the
    // checked state). false greys the switch out instead of letting a
    // flip bounce back — and write to the ship — while another setting
    // overrides this one.
    switchEnabled: Boolean = true,
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
        Switch(checked = enabled, onCheckedChange = onChange, enabled = switchEnabled)
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
        systemState.fullScreenIntentAllowed?.let { allowed ->
            HealthRow(
                label = "Ring over lock screen",
                value = if (allowed) "allowed" else "blocked — fix in Settings",
                highlight = !allowed,
            )
        }
        systemState.callAccountRegistered?.let { registered ->
            HealthRow(
                label = "Phone-app call log",
                value = when {
                    !registered -> "no account — calls aren't logged"
                    systemState.callLogIsVoip -> "logged as VoIP calls — turn Talon on under Calling accounts › Integrated call logs"
                    else -> "account registered"
                },
                highlight = !registered,
            )
        }
        if (systemState.telecomEvents.isNotEmpty()) {
            Text(
                "Telecom, latest first:",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                systemState.telecomEvents.asReversed().joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    val needsFullScreenFix = systemState.fullScreenIntentAllowed == false
    // Fix-it buttons. Render only when there's something to fix
    // AND the probe knows how to deeplink there.
    val needsBatteryFix = systemState.batteryOptimizationsExempt == false
    val needsAppDetails = systemState.notificationsAllowed == false ||
        systemState.backgroundRestricted == true
    if (needsBatteryFix || needsAppDetails || needsFullScreenFix || systemState.callLogIsVoip) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            if (needsFullScreenFix) {
                TextButton(onClick = { probe.openFullScreenIntentSettings() }) {
                    Text("Fix ring")
                }
            }
            if (systemState.callLogIsVoip) {
                TextButton(onClick = { probe.openCallLogIntegrationSettings() }) {
                    Text("Call log")
                }
            }
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
        // Weighted too: an unweighted value measures first and a
        // long one squeezed the label to one letter per line.
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = if (highlight) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun formatHealthAge(ms: Long): String {
    if (ms <= 0L) return "never"
    val now = nowMs()
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

/** The three editable prompt parts, with the built-in default each falls
 *  back to when blank. Drives both the Settings buttons and the editor. */
private class PromptPart(
    val kind: AiSettings.PromptKind,
    val label: String,
    val default: String,
)

private val PROMPT_PARTS = listOf(
    PromptPart(AiSettings.PromptKind.UrbitKnowledge, "Urbit knowledge (shared)", AgentPrompt.urbitKnowledge),
    PromptPart(AiSettings.PromptKind.Assistant, "Assistant prompt", AgentPrompt.assistant),
    PromptPart(AiSettings.PromptKind.Loop, "Scheduled-job prompt", LoopPrompt.loop),
)

/**
 * Full-height editor for the assistant's system prompt. Pre-fills with the
 * current override, or the built-in [default] when none is set so the user
 * edits from a real starting point. Saving text identical to the default
 * stores "" — that keeps the user tracking the maintained default instead
 * of freezing a copy. [onSave] receives the value to persist (sync handles
 * propagation).
 */
@Composable
private fun SystemPromptEditorDialog(
    title: String,
    current: String,
    default: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(current.ifBlank { default }) }
    Dialog(
        onDismissRequest = onDismiss,
        // Default-width dialogs cap at ~280dp — too narrow to edit a
        // multi-paragraph prompt. Let the Surface size itself instead.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.9f),
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "Instructions the AI follows. Tailor it to change behavior, " +
                        "or reset to the built-in default. Edits sync across your devices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        // Unchanged default → store "" so future default
                        // improvements still reach this user.
                        onSave(if (text.trim() == default.trim()) "" else text)
                    }) { Text("Save") }
                    OutlinedButton(onClick = { text = default }) { Text("Reset to default") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

/** Settings groups. Order here is the rail / tab-row order. */
private enum class SettingsTab(val label: String) {
    Appearance("Appearance"),
    Home("Home"),
    Chats("Chats"),
    Notifications("Notifications"),
    Ai("AI"),
    Calls("Calls"),
    Account("Account"),
    About("About"),
}

/** Vertical tab rail — wide layouts (desktop, tablet, landscape). */
@Composable
private fun SettingsRail(
    tabs: List<SettingsTab>,
    selected: SettingsTab,
    onSelect: (SettingsTab) -> Unit,
) {
    Column(
        modifier = Modifier
            .width(172.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        tabs.forEach { t ->
            val on = t == selected
            Surface(
                onClick = { onSelect(t) },
                shape = RoundedCornerShape(10.dp),
                color = if (on) MaterialTheme.colorScheme.secondaryContainer
                else androidx.compose.ui.graphics.Color.Transparent,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
            ) {
                Text(
                    t.label,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = if (on) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** Horizontal scrollable tabs — narrow layouts (phone portrait). */
@Composable
private fun SettingsTabRow(
    tabs: List<SettingsTab>,
    selected: SettingsTab,
    onSelect: (SettingsTab) -> Unit,
) {
    ScrollableTabRow(
        selectedTabIndex = tabs.indexOf(selected).coerceAtLeast(0),
        edgePadding = 12.dp,
    ) {
        tabs.forEach { t ->
            Tab(
                selected = t == selected,
                onClick = { onSelect(t) },
                text = { Text(t.label) },
            )
        }
    }
}

/** A hex field with a swatch; tapping the swatch opens a colour wheel under the row. */
@Composable
private fun ColorRow(label: String, value: String, onValue: (String) -> Unit) {
    val parsed = io.nisfeb.talon.ui.parseHexColor(value)
    var wheelOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(label, Modifier.width(88.dp), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = value,
                onValueChange = onValue,
                placeholder = { Text("#RRGGBB") },
                singleLine = true,
                isError = parsed == null,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(parsed ?: MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { wheelOpen = !wheelOpen },
            )
        }
        if (wheelOpen) {
            io.nisfeb.talon.ui.ColorWheel(
                color = parsed ?: MaterialTheme.colorScheme.primary,
                onColor = { onValue(it.hex()) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Name, light or dark, five colors, and a live preview of the derived scheme. */
@Composable
private fun CustomThemeEditor(
    draft: io.nisfeb.talon.ui.theme.CustomTheme,
    onDraft: (io.nisfeb.talon.ui.theme.CustomTheme) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    androidx.compose.material3.Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { onDraft(draft.copy(name = it)) },
                label = { Text("Theme name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!draft.dark, { onDraft(draft.copy(dark = false)) }, { Text("Light") })
                FilterChip(draft.dark, { onDraft(draft.copy(dark = true)) }, { Text("Dark") })
            }
            ColorRow("Primary", draft.primary) { onDraft(draft.copy(primary = it)) }
            ColorRow("Secondary", draft.secondary) { onDraft(draft.copy(secondary = it)) }
            ColorRow("Tertiary", draft.tertiary) { onDraft(draft.copy(tertiary = it)) }
            ColorRow("Background", draft.background) { onDraft(draft.copy(background = it)) }
            ColorRow("Surface", draft.surface) { onDraft(draft.copy(surface = it)) }
            MaterialTheme(colorScheme = io.nisfeb.talon.ui.theme.customScheme(draft)) {
                androidx.compose.material3.Surface(
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Preview", style = MaterialTheme.typography.titleSmall)
                        androidx.compose.material3.Surface(
                            color = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text("A message on a surface.", Modifier.padding(8.dp))
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.Button(onClick = {}) { Text("Primary") }
                            FilterChip(true, {}, { Text("Selected") })
                            Text("Tertiary", color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Button(onClick = onSave, enabled = draft.valid) { Text("Save and use") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

/**
 * One widget's settings: whether it shows, how much it shows, and the
 * handful of things only it cares about.
 *
 * Order and size are not here. Those are spatial decisions and belong
 * on the page being arranged, where somebody can see what they are
 * doing; a pair of number fields in a settings list would be a worse
 * way to say the same thing.
 */
@Composable
private fun HomeWidgetRow(
    widget: io.nisfeb.talon.ui.HomeWidget,
    pinCandidates: List<Pair<String, String>>,
    onChange: (io.nisfeb.talon.ui.HomeWidget) -> Unit,
) {
    val kind = widget.kind
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    io.nisfeb.talon.ui.screens.title(kind),
                    style = MaterialTheme.typography.bodyMedium
                        .copy(fontWeight = FontWeight.Medium),
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = widget.visible,
                    onCheckedChange = { onChange(widget.copy(visible = it)) },
                )
            }

            if (widget.visible) {
                // The clock shows one thing and the calendar counts in
                // time rather than in rows, so neither has a count.
                if (kind != io.nisfeb.talon.ui.HomeWidgetKind.CLOCK &&
                    kind != io.nisfeb.talon.ui.HomeWidgetKind.CALENDAR
                ) {
                    Text(
                        "How many to show",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        io.nisfeb.talon.ui.HOME_COUNTS.forEach { n ->
                            FilterChip(
                                selected = widget.count == n,
                                onClick = { onChange(widget.copy(count = n)) },
                                label = { Text("$n") },
                            )
                        }
                    }
                }

                if (kind == io.nisfeb.talon.ui.HomeWidgetKind.CALENDAR) {
                    Text(
                        "How far ahead to look",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        io.nisfeb.talon.ui.CalendarRange.entries.forEach { r ->
                            FilterChip(
                                selected = widget.calendarRange == r,
                                onClick = { onChange(widget.copy(calendarRange = r)) },
                                label = { Text(r.label) },
                            )
                        }
                    }
                }

                if (kind == io.nisfeb.talon.ui.HomeWidgetKind.STATUS && pinCandidates.isNotEmpty()) {
                    Text(
                        "Keep at the top",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Pinned people show whether or not they have said anything " +
                            "lately. That is usually the point of pinning them.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        pinCandidates.forEach { (shipId, name) ->
                            val on = shipId in widget.pinned
                            FilterChip(
                                selected = on,
                                onClick = {
                                    val next = if (on) widget.pinned - shipId
                                    else widget.pinned + shipId
                                    onChange(widget.copy(pinned = next))
                                },
                                label = { Text(name) },
                                enabled = on || widget.pinned.size < io.nisfeb.talon.ui.HOME_PINNED_MAX,
                            )
                        }
                    }
                }
            }
        }
    }
}
