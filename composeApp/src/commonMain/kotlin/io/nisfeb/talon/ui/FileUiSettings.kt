package io.nisfeb.talon.ui

import io.nisfeb.talon.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Where a [FileUiSettings] keeps its JSON. Platforms differ only in
 * how they touch the disk — desktop writes through java.nio with an
 * atomic move, iOS through okio under Documents — so that is the only
 * thing they supply.
 */
interface UiSettingsStore {
    fun read(): String?
    fun write(text: String)
}

/**
 * JSON-backed UI settings, shared by every platform that has a disk.
 * Writes go through [UiSettingsStore] so a crash mid-write can't
 * truncate the file to an unparseable state.
 *
 * [railVisibility] is the exception — its rows live in the per-ship
 * Room database (sparse `rail_item_prefs` table) so they can sync via
 * %settings. The flow here is a read-only projection of that table.
 * Mutation goes through `SettingsSyncImpl.setRailItemVisibility`.
 */
class FileUiSettings(
    private val store: UiSettingsStore,
    private val db: AppDatabase,
    private val scope: CoroutineScope,
) : UiSettings {
    @Serializable
    private data class Persisted(
        val hideComposerButtons: Boolean = false,
        // Accent settings — `enabled` is nullable so we can tell
        // "user never opted in" apart from "user explicitly off",
        // matching the contract in [AccentSettings].
        val accentEnabled: Boolean? = null,
        val accentMode: String = AccentMode.Profile.name,
        val accentCustomHex: String? = null,
        // Channel ordering inside group dropdowns on the home list.
        val groupChannelOrder: String = GroupChannelOrder.Recent.name,
        // Top-level item ordering inside custom folders and the
        // home list's Groups section. Default Manual keeps the
        // pre-existing saved-order behavior so the setting is opt-in.
        val folderItemOrder: String = FolderItemOrder.Manual.name,
        // Fraction of total width given to the chat-list pane on wide windows.
        val chatPaneListFraction: Float = 0.30f,
        val activeRailTab: String = RailTab.Chats.name,
        val smartSearchPreferred: Boolean = false,
        val homePlace: String = "",
        val homeFahrenheit: Boolean = true,
        val homeTwentyFourHour: Boolean = false,
        val homeLayout: String = "",
        val railItemOrder: List<String> = emptyList(),
        val powerFeaturesEnabled: Boolean = false,
        val density: String = Density.Comfortable.name,
        val fontScale: Float = 1.0f,
        val alwaysPatp: Boolean = false,
        /** [io.nisfeb.talon.ui.theme.ThemeSettings] as JSON text. */
        val customThemes: String = "",
        val micNoiseSuppression: Boolean = true,
        val micEchoCancellation: Boolean = true,
        val micAutoGainControl: Boolean = true,
    )

    private val initial = loadInitial()

    init {
        ShipNames.alwaysPatp.value = initial.alwaysPatp
        ShipNames.persist = { persistCurrent() }
    }
    private val _hideComposerButtons = MutableStateFlow(initial.hideComposerButtons)
    override val hideComposerButtons: StateFlow<Boolean> =
        _hideComposerButtons.asStateFlow()

    private val _accentSettings = MutableStateFlow(
        AccentSettings(
            enabled = initial.accentEnabled,
            mode = runCatching { AccentMode.valueOf(initial.accentMode) }
                .getOrDefault(AccentMode.Profile),
            customHex = initial.accentCustomHex,
        ),
    )
    override val accentSettings: StateFlow<AccentSettings> =
        _accentSettings.asStateFlow()
    private val _themeSettings = MutableStateFlow(
        io.nisfeb.talon.ui.theme.ThemeSettings.fromJson(initial.customThemes)
            ?: io.nisfeb.talon.ui.theme.ThemeSettings(),
    )
    override val themeSettings: StateFlow<io.nisfeb.talon.ui.theme.ThemeSettings> =
        _themeSettings.asStateFlow()
    override fun setThemeSettings(settings: io.nisfeb.talon.ui.theme.ThemeSettings) {
        if (_themeSettings.value == settings) return
        _themeSettings.value = settings
        persistCurrent()
    }
    private val _micProcessing = MutableStateFlow(
        io.nisfeb.talon.call.MicProcessing(
            noiseSuppression = initial.micNoiseSuppression,
            echoCancellation = initial.micEchoCancellation,
            autoGainControl = initial.micAutoGainControl,
        ),
    )
    override val micProcessing: StateFlow<io.nisfeb.talon.call.MicProcessing> = _micProcessing.asStateFlow()
    override fun setMicProcessing(value: io.nisfeb.talon.call.MicProcessing) {
        if (_micProcessing.value == value) return
        _micProcessing.value = value
        persistCurrent()
    }

    private val _groupChannelOrder = MutableStateFlow(
        runCatching { GroupChannelOrder.valueOf(initial.groupChannelOrder) }
            .getOrDefault(GroupChannelOrder.Recent),
    )
    override val groupChannelOrder: StateFlow<GroupChannelOrder> =
        _groupChannelOrder.asStateFlow()

    private val _folderItemOrder = MutableStateFlow(
        runCatching { FolderItemOrder.valueOf(initial.folderItemOrder) }
            .getOrDefault(FolderItemOrder.Manual),
    )
    override val folderItemOrder: StateFlow<FolderItemOrder> =
        _folderItemOrder.asStateFlow()

    private val _chatPaneListFraction = MutableStateFlow(
        initial.chatPaneListFraction.coerceIn(0.20f, 0.50f),
    )
    override val chatPaneListFraction: StateFlow<Float> =
        _chatPaneListFraction.asStateFlow()

    private val _activeRailTab = MutableStateFlow(
        railTabOrDefault(initial.activeRailTab),
    )
    override val activeRailTab: StateFlow<RailTab> =
        _activeRailTab.asStateFlow()

    private val _homePlace = MutableStateFlow(initial.homePlace)
    override val homePlace: StateFlow<String> = _homePlace.asStateFlow()
    override fun setHomePlace(encoded: String) {
        if (_homePlace.value == encoded) return
        _homePlace.value = encoded
        persistCurrent()
    }

    private val _homeFahrenheit = MutableStateFlow(initial.homeFahrenheit)
    override val homeFahrenheit: StateFlow<Boolean> = _homeFahrenheit.asStateFlow()
    override fun setHomeFahrenheit(on: Boolean) {
        if (_homeFahrenheit.value == on) return
        _homeFahrenheit.value = on
        persistCurrent()
    }

    private val _homeTwentyFourHour = MutableStateFlow(initial.homeTwentyFourHour)
    override val homeTwentyFourHour: StateFlow<Boolean> = _homeTwentyFourHour.asStateFlow()
    override fun setHomeTwentyFourHour(on: Boolean) {
        if (_homeTwentyFourHour.value == on) return
        _homeTwentyFourHour.value = on
        persistCurrent()
    }

    private val _homeLayout = MutableStateFlow(initial.homeLayout)
    override val homeLayout: StateFlow<String> = _homeLayout.asStateFlow()
    override fun setHomeLayout(encoded: String) {
        if (_homeLayout.value == encoded) return
        _homeLayout.value = encoded
        persistCurrent()
    }

    private val _smartSearchPreferred = MutableStateFlow(initial.smartSearchPreferred)
    override val smartSearchPreferred: StateFlow<Boolean> =
        _smartSearchPreferred.asStateFlow()

    private val _powerFeaturesEnabled = MutableStateFlow(initial.powerFeaturesEnabled)
    override val powerFeaturesEnabled: StateFlow<Boolean> =
        _powerFeaturesEnabled.asStateFlow()

    private val _density = MutableStateFlow(
        runCatching { Density.valueOf(initial.density) }
            .getOrDefault(Density.Comfortable),
    )
    override val density: StateFlow<Density> = _density.asStateFlow()

    private val _fontScale = MutableStateFlow(normalizeFontScale(initial.fontScale))
    override val fontScale: StateFlow<Float> = _fontScale.asStateFlow()

    private val _railItemOrder = MutableStateFlow(
        sanitizeRailItemOrder(
            initial.railItemOrder.mapNotNull { railItemOrNull(it) },
        ),
    )
    override val railItemOrder: StateFlow<List<RailItem>> = _railItemOrder.asStateFlow()

    // Read-only projection of the per-ship rail_item_prefs table.
    // Sparse — only rows the user has explicitly hidden. Eager so the
    // flow stays subscribed for the lifetime of [scope] and the first
    // composition collect doesn't pay a fresh DAO subscribe.
    override val railVisibility: StateFlow<Map<RailItem, Boolean>> =
        db.railItemPrefs().streamAll()
            .map(::railVisibilityFromRows)
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    override fun setHideComposerButtons(hidden: Boolean) {
        if (_hideComposerButtons.value == hidden) return
        _hideComposerButtons.value = hidden
        persistCurrent()
    }

    override fun setAccentSettings(settings: AccentSettings) {
        if (_accentSettings.value == settings) return
        _accentSettings.value = settings
        persistCurrent()
    }

    override fun setGroupChannelOrder(order: GroupChannelOrder) {
        if (_groupChannelOrder.value == order) return
        _groupChannelOrder.value = order
        persistCurrent()
    }

    override fun setFolderItemOrder(order: FolderItemOrder) {
        if (_folderItemOrder.value == order) return
        _folderItemOrder.value = order
        persistCurrent()
    }

    override fun setChatPaneListFraction(value: Float) {
        val clamped = value.coerceIn(0.20f, 0.50f)
        if (_chatPaneListFraction.value == clamped) return
        _chatPaneListFraction.value = clamped
        persistCurrent()
    }

    override fun setActiveRailTab(tab: RailTab) {
        if (_activeRailTab.value == tab) return
        _activeRailTab.value = tab
        persistCurrent()
    }

    override fun setSmartSearchPreferred(preferred: Boolean) {
        if (_smartSearchPreferred.value == preferred) return
        _smartSearchPreferred.value = preferred
        persistCurrent()
    }

    override fun setPowerFeaturesEnabled(enabled: Boolean) {
        if (_powerFeaturesEnabled.value == enabled) return
        _powerFeaturesEnabled.value = enabled
        persistCurrent()
    }

    override fun setDensity(mode: Density) {
        if (_density.value == mode) return
        _density.value = mode
        persistCurrent()
    }

    override fun setFontScale(scale: Float) {
        val v = normalizeFontScale(scale)
        if (_fontScale.value == v) return
        _fontScale.value = v
        persistCurrent()
    }

    override fun setRailItemOrder(items: List<RailItem>) {
        val sanitized = sanitizeRailItemOrder(items)
        if (_railItemOrder.value == sanitized) return
        _railItemOrder.value = sanitized
        persistCurrent()
    }

    private fun persistCurrent() {
        val accent = _accentSettings.value
        persist(
            Persisted(
                hideComposerButtons = _hideComposerButtons.value,
                accentEnabled = accent.enabled,
                accentMode = accent.mode.name,
                accentCustomHex = accent.customHex,
                groupChannelOrder = _groupChannelOrder.value.name,
                folderItemOrder = _folderItemOrder.value.name,
                chatPaneListFraction = _chatPaneListFraction.value,
                activeRailTab = _activeRailTab.value.name,
                smartSearchPreferred = _smartSearchPreferred.value,
                homePlace = _homePlace.value,
                homeFahrenheit = _homeFahrenheit.value,
                homeTwentyFourHour = _homeTwentyFourHour.value,
                homeLayout = _homeLayout.value,
                railItemOrder = _railItemOrder.value.map { it.name },
                powerFeaturesEnabled = _powerFeaturesEnabled.value,
                density = _density.value.name,
                fontScale = _fontScale.value,
                alwaysPatp = ShipNames.alwaysPatp.value,
                customThemes = _themeSettings.value.toJson(),
                micNoiseSuppression = _micProcessing.value.noiseSuppression,
                micEchoCancellation = _micProcessing.value.echoCancellation,
                micAutoGainControl = _micProcessing.value.autoGainControl,
            ),
        )
    }

    private fun loadInitial(): Persisted {
        val text = runCatching { store.read() }.getOrNull() ?: return Persisted()
        return runCatching { JSON.decodeFromString<Persisted>(text) }
            .getOrElse { Persisted() }
    }

    private fun persist(value: Persisted) {
        runCatching { store.write(JSON.encodeToString(value)) }
    }

    private companion object {
        private val JSON = Json { ignoreUnknownKeys = true; prettyPrint = false }
    }
}
