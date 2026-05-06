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
    private val prefs = context.getSharedPreferences("talon.ui", Context.MODE_PRIVATE)

    private val _hideComposerButtons = MutableStateFlow(
        prefs.getBoolean(KEY_HIDE_COMPOSER_BUTTONS, false),
    )
    override val hideComposerButtons: StateFlow<Boolean> =
        _hideComposerButtons.asStateFlow()

    private val _accentSettings = MutableStateFlow(loadAccent())
    override val accentSettings: StateFlow<AccentSettings> =
        _accentSettings.asStateFlow()

    private val _groupChannelOrder = MutableStateFlow(loadGroupOrder())
    override val groupChannelOrder: StateFlow<GroupChannelOrder> =
        _groupChannelOrder.asStateFlow()

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

    private val _smartSearchPreferred = MutableStateFlow(
        prefs.getBoolean(KEY_SMART_SEARCH_PREFERRED, false),
    )
    override val smartSearchPreferred: StateFlow<Boolean> =
        _smartSearchPreferred.asStateFlow()

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
            .map { rows ->
                rows.mapNotNull { row ->
                    val item = railItemOrNull(row.itemName) ?: return@mapNotNull null
                    item to row.visible
                }.toMap()
            }
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
        private const val KEY_ACCENT_ENABLED = "accent_enabled"
        private const val KEY_ACCENT_MODE = "accent_mode"
        private const val KEY_ACCENT_HEX = "accent_hex"
        private const val KEY_GROUP_CHANNEL_ORDER = "group_channel_order"
        private const val KEY_CHAT_PANE_LIST_FRACTION = "chat_pane_list_fraction"
        private const val KEY_ACTIVE_RAIL_TAB = "active_rail_tab"
        private const val KEY_SMART_SEARCH_PREFERRED = "smart_search_preferred"
        private const val KEY_RAIL_ITEM_ORDER = "rail_item_order"
    }
}
