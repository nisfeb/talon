package io.nisfeb.talon.ui

import android.content.Context
import io.nisfeb.talon.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Android implementation of [UiSettings] — SharedPreferences-backed.
 * Mirrors the production `class UiSettings(context)` in app/ that
 * this replaces post-Stage-F. Desktop counterpart is the JSON-file
 * [DesktopUiSettings] in desktopMain.
 *
 * [railVisibility] is sourced from the per-ship Room database
 * (`rail_item_prefs` table) rather than SharedPreferences so it can
 * sync via %settings. Mutation goes through
 * `SettingsSyncImpl.setRailItemVisibility`.
 *
 * Per-ship retargetting: unlike desktop (which builds a fresh
 * UiSettings per ship inside the composable's `key` block via
 * `createUiSettings`), Android holds a single process-wide
 * [AndroidUiSettings] in [TalonApplication]. Ship-switch rebuilds the
 * underlying [AppDatabase], so [rebindDb] retargets the
 * `railVisibility` flow to the new ship's `rail_item_prefs` table.
 * The other fields are SharedPreferences-backed and per-DEVICE — they
 * don't need retargetting. Keeping the instance stable means
 * subscribers outside the `key(loggedInShip)` subtree (e.g.
 * MainActivity reading accentSettings for the system theme) don't get
 * orphaned on switch.
 */
class AndroidUiSettings(
    context: Context,
    initialDb: AppDatabase,
    scope: CoroutineScope,
) : UiSettings {
    private val prefs = context.getSharedPreferences(HomePrefs.FILE, Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

    /**
     * The home-screen widget reads these same preferences from another
     * process, and has no way of knowing they changed. Without this it
     * would go on showing the old place until its half-hourly update
     * came round, which looks like the setting not having taken.
     */
    private fun nudgeWidget() {
        runCatching { io.nisfeb.talon.widget.ClockWidgetProvider.nudge(appContext) }
    }

    init {
        // Mnemonym naming: the runtime switch lives in the shared
        // [MnemonymNames] object (ContactMap reads it); this store just
        // loads the persisted choice over the default and keeps writes.
        MnemonymNames.enabled.value = prefs.getBoolean(KEY_MNEMONYM_NAMES, true)
        MnemonymNames.persist = { v ->
            prefs.edit().putBoolean(KEY_MNEMONYM_NAMES, v).apply()
        }
        ShipNames.alwaysPatp.value = prefs.getBoolean(KEY_ALWAYS_PATP, false)
        ShipNames.persist = { v ->
            prefs.edit().putBoolean(KEY_ALWAYS_PATP, v).apply()
        }
    }

    private val _hideComposerButtons = MutableStateFlow(
        prefs.getBoolean(KEY_HIDE_COMPOSER_BUTTONS, false),
    )
    override val hideComposerButtons: StateFlow<Boolean> =
        _hideComposerButtons.asStateFlow()

    private val _accentSettings = MutableStateFlow(loadAccent())
    override val accentSettings: StateFlow<AccentSettings> =
        _accentSettings.asStateFlow()
    private val _themeSettings = MutableStateFlow(
        io.nisfeb.talon.ui.theme.ThemeSettings.fromJson(prefs.getString(KEY_THEMES, null))
            ?: io.nisfeb.talon.ui.theme.ThemeSettings(),
    )
    override val themeSettings: StateFlow<io.nisfeb.talon.ui.theme.ThemeSettings> =
        _themeSettings.asStateFlow()
    override fun setThemeSettings(settings: io.nisfeb.talon.ui.theme.ThemeSettings) {
        if (_themeSettings.value == settings) return
        prefs.edit().putString(KEY_THEMES, settings.toJson()).apply()
        _themeSettings.value = settings
    }
    private val _micProcessing = MutableStateFlow(
        io.nisfeb.talon.call.MicProcessing(
            noiseSuppression = prefs.getBoolean(KEY_MIC_NS, true),
            echoCancellation = prefs.getBoolean(KEY_MIC_AEC, true),
            autoGainControl = prefs.getBoolean(KEY_MIC_AGC, true),
        ),
    )
    override val micProcessing: StateFlow<io.nisfeb.talon.call.MicProcessing> = _micProcessing.asStateFlow()
    override fun setMicProcessing(value: io.nisfeb.talon.call.MicProcessing) {
        if (_micProcessing.value == value) return
        prefs.edit()
            .putBoolean(KEY_MIC_NS, value.noiseSuppression)
            .putBoolean(KEY_MIC_AEC, value.echoCancellation)
            .putBoolean(KEY_MIC_AGC, value.autoGainControl)
            .apply()
        _micProcessing.value = value
    }

    private val _groupChannelOrder = MutableStateFlow(loadGroupOrder())
    override val groupChannelOrder: StateFlow<GroupChannelOrder> =
        _groupChannelOrder.asStateFlow()

    private val _folderItemOrder = MutableStateFlow(loadFolderItemOrder())
    override val folderItemOrder: StateFlow<FolderItemOrder> =
        _folderItemOrder.asStateFlow()

    private val _chatPaneListFraction = MutableStateFlow(
        prefs.getFloat(KEY_CHAT_PANE_LIST_FRACTION, 0.30f).coerceIn(0.20f, 0.50f),
    )
    override val chatPaneListFraction: StateFlow<Float> =
        _chatPaneListFraction.asStateFlow()

    private val _activeRailTab = MutableStateFlow(
        railTabOrDefault(prefs.getString(KEY_ACTIVE_RAIL_TAB, null)),
    )
    override val activeRailTab: StateFlow<RailTab> =
        _activeRailTab.asStateFlow()

    private val _homePlace = MutableStateFlow(prefs.getString(KEY_HOME_PLACE, "") ?: "")
    override val homePlace: StateFlow<String> = _homePlace.asStateFlow()
    override fun setHomePlace(encoded: String) {
        if (_homePlace.value == encoded) return
        _homePlace.value = encoded
        prefs.edit().putString(KEY_HOME_PLACE, encoded).apply()
        nudgeWidget()
    }

    private val _homeFahrenheit = MutableStateFlow(prefs.getBoolean(KEY_HOME_FAHRENHEIT, true))
    override val homeFahrenheit: StateFlow<Boolean> = _homeFahrenheit.asStateFlow()
    override fun setHomeFahrenheit(on: Boolean) {
        if (_homeFahrenheit.value == on) return
        _homeFahrenheit.value = on
        prefs.edit().putBoolean(KEY_HOME_FAHRENHEIT, on).apply()
        nudgeWidget()
    }

    private val _homeLayout = MutableStateFlow(prefs.getString(KEY_HOME_LAYOUT, "") ?: "")
    override val homeLayout: StateFlow<String> = _homeLayout.asStateFlow()
    override fun setHomeLayout(encoded: String) {
        if (_homeLayout.value == encoded) return
        _homeLayout.value = encoded
        prefs.edit().putString(KEY_HOME_LAYOUT, encoded).apply()
    }

    private val _homeTwentyFourHour = MutableStateFlow(prefs.getBoolean(KEY_HOME_24H, false))
    override val homeTwentyFourHour: StateFlow<Boolean> = _homeTwentyFourHour.asStateFlow()
    override fun setHomeTwentyFourHour(on: Boolean) {
        if (_homeTwentyFourHour.value == on) return
        _homeTwentyFourHour.value = on
        prefs.edit().putBoolean(KEY_HOME_24H, on).apply()
        nudgeWidget()
    }

    private val _smartSearchPreferred = MutableStateFlow(
        prefs.getBoolean(KEY_SMART_SEARCH_PREFERRED, false),
    )
    override val smartSearchPreferred: StateFlow<Boolean> =
        _smartSearchPreferred.asStateFlow()

    private val _powerFeaturesEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_POWER_FEATURES, false),
    )
    override val powerFeaturesEnabled: StateFlow<Boolean> =
        _powerFeaturesEnabled.asStateFlow()

    private val _density = MutableStateFlow(loadDensity())
    override val density: StateFlow<Density> = _density.asStateFlow()

    private val _railItemOrder = MutableStateFlow(
        sanitizeRailItemOrder(loadRailItemOrder(prefs)),
    )
    override val railItemOrder: StateFlow<List<RailItem>> = _railItemOrder.asStateFlow()

    // Tracks the active ship's [AppDatabase]. Updated by [rebindDb]
    // from TalonApplication.buildShipScoped on every ship switch so
    // [railVisibility] re-subscribes to the new ship's table.
    private val dbFlow = MutableStateFlow(initialDb)

    /**
     * Retarget [railVisibility] at [db]. Called by
     * `TalonApplication.buildShipScoped` after a ship switch rebuilds
     * the per-ship [AppDatabase]. Cheap — `flatMapLatest` cancels the
     * old DAO subscription and starts the new one on the same scope.
     */
    fun rebindDb(db: AppDatabase) {
        dbFlow.value = db
    }

    // Read-only projection of the per-ship rail_item_prefs table.
    // Sparse — only rows the user has explicitly hidden. Eager so the
    // flow stays subscribed for the lifetime of [scope] and the first
    // composition collect doesn't pay a fresh DAO subscribe.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val railVisibility: StateFlow<Map<RailItem, Boolean>> =
        dbFlow
            .flatMapLatest { db -> db.railItemPrefs().streamAll() }
            .map(::railVisibilityFromRows)
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    override fun setHideComposerButtons(hidden: Boolean) {
        if (_hideComposerButtons.value == hidden) return
        prefs.edit().putBoolean(KEY_HIDE_COMPOSER_BUTTONS, hidden).apply()
        _hideComposerButtons.value = hidden
    }

    override fun setAccentSettings(settings: AccentSettings) {
        if (_accentSettings.value == settings) return
        prefs.edit().apply {
            // SharedPreferences has no nullable Boolean — we encode it
            // as Int: -1 for null (never opted in), 0 for false, 1 for
            // true. Matches the contract on AccentSettings.enabled.
            putInt(
                KEY_ACCENT_ENABLED,
                when (settings.enabled) {
                    null -> -1
                    false -> 0
                    true -> 1
                },
            )
            putString(KEY_ACCENT_MODE, settings.mode.name)
            if (settings.customHex == null) remove(KEY_ACCENT_HEX)
            else putString(KEY_ACCENT_HEX, settings.customHex)
        }.apply()
        _accentSettings.value = settings
    }

    override fun setGroupChannelOrder(order: GroupChannelOrder) {
        if (_groupChannelOrder.value == order) return
        prefs.edit().putString(KEY_GROUP_CHANNEL_ORDER, order.name).apply()
        _groupChannelOrder.value = order
    }

    override fun setFolderItemOrder(order: FolderItemOrder) {
        if (_folderItemOrder.value == order) return
        prefs.edit().putString(KEY_FOLDER_ITEM_ORDER, order.name).apply()
        _folderItemOrder.value = order
    }

    override fun setChatPaneListFraction(value: Float) {
        val clamped = value.coerceIn(0.20f, 0.50f)
        if (_chatPaneListFraction.value == clamped) return
        prefs.edit().putFloat(KEY_CHAT_PANE_LIST_FRACTION, clamped).apply()
        _chatPaneListFraction.value = clamped
    }

    override fun setActiveRailTab(tab: RailTab) {
        if (_activeRailTab.value == tab) return
        prefs.edit().putString(KEY_ACTIVE_RAIL_TAB, tab.name).apply()
        _activeRailTab.value = tab
    }

    override fun setPowerFeaturesEnabled(enabled: Boolean) {
        if (_powerFeaturesEnabled.value == enabled) return
        prefs.edit().putBoolean(KEY_POWER_FEATURES, enabled).apply()
        _powerFeaturesEnabled.value = enabled
    }

    override fun setDensity(mode: Density) {
        if (_density.value == mode) return
        prefs.edit().putString(KEY_DENSITY, mode.name).apply()
        _density.value = mode
    }

    private val _fontScale = MutableStateFlow(
        normalizeFontScale(prefs.getFloat(KEY_FONT_SCALE, 1.0f)),
    )
    override val fontScale: StateFlow<Float> = _fontScale.asStateFlow()
    override fun setFontScale(scale: Float) {
        val v = normalizeFontScale(scale)
        if (_fontScale.value == v) return
        prefs.edit().putFloat(KEY_FONT_SCALE, v).apply()
        _fontScale.value = v
    }

    private fun loadDensity(): Density {
        val raw = prefs.getString(KEY_DENSITY, null) ?: return Density.Comfortable
        return runCatching { Density.valueOf(raw) }.getOrDefault(Density.Comfortable)
    }

    override fun setSmartSearchPreferred(preferred: Boolean) {
        if (_smartSearchPreferred.value == preferred) return
        prefs.edit().putBoolean(KEY_SMART_SEARCH_PREFERRED, preferred).apply()
        _smartSearchPreferred.value = preferred
    }

    override fun setRailItemOrder(items: List<RailItem>) {
        val sanitized = sanitizeRailItemOrder(items)
        if (_railItemOrder.value == sanitized) return
        prefs.edit()
            .putString(KEY_RAIL_ITEM_ORDER, sanitized.joinToString(",") { it.name })
            .apply()
        _railItemOrder.value = sanitized
    }

    private fun loadGroupOrder(): GroupChannelOrder {
        val name = prefs.getString(KEY_GROUP_CHANNEL_ORDER, null) ?: return GroupChannelOrder.Recent
        return runCatching { GroupChannelOrder.valueOf(name) }
            .getOrDefault(GroupChannelOrder.Recent)
    }

    private fun loadFolderItemOrder(): FolderItemOrder {
        val name = prefs.getString(KEY_FOLDER_ITEM_ORDER, null) ?: return FolderItemOrder.Manual
        return runCatching { FolderItemOrder.valueOf(name) }
            .getOrDefault(FolderItemOrder.Manual)
    }

    private fun loadRailItemOrder(prefs: android.content.SharedPreferences): List<RailItem> {
        val raw = prefs.getString(KEY_RAIL_ITEM_ORDER, null) ?: return RailItem.entries
        return raw.split(",").mapNotNull { railItemOrNull(it.trim()) }
    }

    private fun loadAccent(): AccentSettings {
        val rawEnabled = prefs.getInt(KEY_ACCENT_ENABLED, -1)
        val enabled = when (rawEnabled) {
            0 -> false
            1 -> true
            else -> null
        }
        val mode = runCatching {
            AccentMode.valueOf(prefs.getString(KEY_ACCENT_MODE, null) ?: AccentMode.Profile.name)
        }.getOrDefault(AccentMode.Profile)
        return AccentSettings(
            enabled = enabled,
            mode = mode,
            customHex = prefs.getString(KEY_ACCENT_HEX, null),
        )
    }

    private companion object {
        private const val KEY_HIDE_COMPOSER_BUTTONS = "hide_composer_buttons"
        private const val KEY_HOME_PLACE = HomePrefs.PLACE
        private const val KEY_HOME_FAHRENHEIT = HomePrefs.FAHRENHEIT
        private const val KEY_HOME_24H = HomePrefs.TWENTY_FOUR_HOUR
        private const val KEY_HOME_LAYOUT = "home_layout"
        private const val KEY_ACCENT_ENABLED = "accent_enabled"
        private const val KEY_ACCENT_MODE = "accent_mode"
        private const val KEY_ACCENT_HEX = "accent_hex"
        private const val KEY_THEMES = "custom_themes"
private const val KEY_MIC_NS = "mic_noise_suppression"
private const val KEY_MIC_AEC = "mic_echo_cancellation"
private const val KEY_MIC_AGC = "mic_auto_gain"
        private const val KEY_GROUP_CHANNEL_ORDER = "group_channel_order"
        private const val KEY_FOLDER_ITEM_ORDER = "folder_item_order"
        private const val KEY_CHAT_PANE_LIST_FRACTION = "chat_pane_list_fraction"
        private const val KEY_ACTIVE_RAIL_TAB = "active_rail_tab"
        private const val KEY_SMART_SEARCH_PREFERRED = "smart_search_preferred"
        private const val KEY_POWER_FEATURES = "power_features_enabled"
        private const val KEY_DENSITY = "density"
        private const val KEY_FONT_SCALE = "font_scale"
        private const val KEY_RAIL_ITEM_ORDER = "rail_item_order"
        private const val KEY_ALWAYS_PATP = "always_patp"
private const val KEY_MNEMONYM_NAMES = "mnemonym_names"
    }
}

/**
 * The names the home-page settings are stored under.
 *
 * Shared rather than repeated, because the home-screen widget is a
 * second reader of the same preferences and it reads them from a
 * different process. A widget with its own spelling of the file name
 * finds nothing and says so by showing no location at all, for ever,
 * with nothing to suggest why.
 */
internal object HomePrefs {
    const val FILE = "talon.ui"
    const val PLACE = "home_place"
    const val FAHRENHEIT = "home_fahrenheit"
    const val TWENTY_FOUR_HOUR = "home_24h"
}
