package io.nisfeb.talon.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UI-only preferences (composer toggles etc). Non-sensitive — desktop
 * persists to a JSON file, production Android backs with
 * SharedPreferences. Tests get the in-memory default.
 */
interface UiSettings {
    val hideComposerButtons: StateFlow<Boolean>
    fun setHideComposerButtons(hidden: Boolean)

    /**
     * Theme accent color preferences. The effective tint that drives
     * every primary-colored UI element (send icon, focus rings, ship
     * pip, FilterChip selected state, etc) is derived from this plus
     * the runtime ship-count signal — see [AccentSettings.effective].
     *
     * `null` for any nullable field means "unset, use auto default".
     * The auto default depends on whether the user is logged into
     * multiple ships: multi-ship users default to per-ship Profile
     * tinting (so existing users aren't impacted), single-ship users
     * default to the brand color.
     */
    val accentSettings: StateFlow<AccentSettings>
    fun setAccentSettings(settings: AccentSettings)

    /**
     * How channels nested under a group head sort in the home list.
     * Default [GroupChannelOrder.Recent] keeps the historical
     * "unread first, then most-recently-active" behavior. Switching
     * to [GroupChannelOrder.HostOrder] sorts by the host-defined
     * ordinal captured at bootstrap (ChannelGroupEntity.ordinal).
     * Unread channels still float to the top in either mode.
     */
    val groupChannelOrder: StateFlow<GroupChannelOrder>
    fun setGroupChannelOrder(order: GroupChannelOrder)

    /**
     * Ratio of total width given to the chat-list pane on wide
     * windows (≥840dp). Clamped to 0.20–0.50 at the setter; corrupt
     * values from disk get normalised on next write. See
     * [io.nisfeb.talon.ui.ChatPaneScaffold] / `DEFAULT_LIST_FRACTION`.
     */
    val chatPaneListFraction: StateFlow<Float>
    fun setChatPaneListFraction(value: Float)

    /**
     * Which surface the desktop / tablet-landscape rail has selected for
     * the left pane. Default [RailTab.Chats]. Persists per ship across
     * launches alongside the other UI prefs. Mobile (<840dp) ignores
     * this value — the kebab menu drives mobile nav directly via the
     * existing `showStatusFeed` / `showBookmarks` / `showActivity`
     * flags. See `DesktopShell` for the wide-pane consumer.
     */
    val activeRailTab: StateFlow<RailTab>
    fun setActiveRailTab(tab: RailTab)

    /**
     * Visibility overrides for [RailItem]s. Sparse Map — only contains
     * rows for items the user has explicitly hidden. Read sites should
     * use [Map.isVisible] (in `RailItem.kt`) which defaults absent
     * items to true.
     *
     * Mutation goes through [io.nisfeb.talon.urbit.SettingsSync]'s
     * `setRailItemVisibility` (which writes the underlying Room table
     * + pokes %settings for cross-device sync). UiSettings exposes the
     * read-side flow only.
     */
    val railVisibility: StateFlow<Map<RailItem, Boolean>>

    /**
     * Per-device "use smart search by default" preference. Drives the
     * Search screen's smart-mode chip selection so it persists across
     * navigation. Independent of [io.nisfeb.talon.ai.AiSettings.Feature.SemanticSearch]
     * — that flag controls whether the feature is *available* (and
     * runs the indexer); this one tracks whether the user wants it
     * *active for their searches*. Only meaningful when the AI flag is
     * on; the chip is hidden otherwise.
     */
    val smartSearchPreferred: StateFlow<Boolean>
    fun setSmartSearchPreferred(preferred: Boolean)

    /**
     * Per-device preferred order of rail items. Drives both the
     * desktop rail rendering and the kebab overflow ordering. Stored
     * locally rather than via %settings — putting it in `rail_item_prefs`
     * would bump the DB version and risk wiping desktop data through
     * `fallbackToDestructiveMigration`. The trade-off: each device
     * configures its own rail order. Visibility still syncs.
     *
     * Default = [RailItem.entries] in declaration order. Returns a
     * sanitized list — exactly the set of [RailItem] values, no
     * duplicates, in the user's preferred sequence. New enum values
     * added in future versions get appended at their declaration
     * position so they show up without forcing the user to reset.
     */
    val railItemOrder: StateFlow<List<RailItem>>
    fun setRailItemOrder(items: List<RailItem>)

    /**
     * Per-device power-user opt-in. Off by default; flipping on
     * surfaces advanced surfaces that can poke arbitrary agents on
     * the user's ship — currently the `/poke <app> <mark> <noun>`
     * slash command. Per-device (not synced) so a toggle on phone
     * doesn't grant the same surface on desktop without explicit
     * consent on each device.
     */
    val powerFeaturesEnabled: StateFlow<Boolean>
    fun setPowerFeaturesEnabled(enabled: Boolean)
}

enum class GroupChannelOrder { Recent, HostOrder }

/**
 * How the active ship's accent gets resolved to a single color.
 *
 * - [Brand] — always use the Talon brand primary. The off state.
 * - [Profile] — use the active ship's `%contacts` color (the per-ship
 *   visual cue users have leaned on for multi-ship sessions).
 * - [Custom] — use [AccentSettings.customHex] verbatim.
 */
enum class AccentMode { Brand, Profile, Custom }

/**
 * Stored shape; [enabled] starts null so we can distinguish "user
 * never touched this setting" from "user explicitly chose off". The
 * latter must survive a single-ship → multi-ship transition without
 * silently flipping back on.
 */
data class AccentSettings(
    val enabled: Boolean? = null,
    val mode: AccentMode = AccentMode.Profile,
    val customHex: String? = null,
) {
    companion object {
        /**
         * Effective on/off: stored value when set, otherwise true
         * for multi-ship users (preserves the v0.7.x per-ship pip /
         * send tint they're used to) and false for single-ship.
         */
        fun isEnabled(stored: AccentSettings, multiShip: Boolean): Boolean =
            stored.enabled ?: multiShip
    }
}

class InMemoryUiSettings(
    initialHide: Boolean = false,
    initialAccent: AccentSettings = AccentSettings(),
    initialGroupOrder: GroupChannelOrder = GroupChannelOrder.Recent,
    initialChatPaneListFraction: Float = 0.30f,
    initialActiveRailTab: RailTab = RailTab.Chats,
    initialRailVisibility: Map<RailItem, Boolean> = emptyMap(),
    initialSmartSearchPreferred: Boolean = false,
    initialRailItemOrder: List<RailItem> = RailItem.entries,
) : UiSettings {
    private val _hideComposerButtons = MutableStateFlow(initialHide)
    override val hideComposerButtons: StateFlow<Boolean> =
        _hideComposerButtons.asStateFlow()
    override fun setHideComposerButtons(hidden: Boolean) {
        _hideComposerButtons.value = hidden
    }

    private val _accentSettings = MutableStateFlow(initialAccent)
    override val accentSettings: StateFlow<AccentSettings> =
        _accentSettings.asStateFlow()
    override fun setAccentSettings(settings: AccentSettings) {
        _accentSettings.value = settings
    }

    private val _groupChannelOrder = MutableStateFlow(initialGroupOrder)
    override val groupChannelOrder: StateFlow<GroupChannelOrder> =
        _groupChannelOrder.asStateFlow()
    override fun setGroupChannelOrder(order: GroupChannelOrder) {
        _groupChannelOrder.value = order
    }

    private val _chatPaneListFraction = MutableStateFlow(
        initialChatPaneListFraction.coerceIn(0.20f, 0.50f),
    )
    override val chatPaneListFraction: StateFlow<Float> =
        _chatPaneListFraction.asStateFlow()
    override fun setChatPaneListFraction(value: Float) {
        _chatPaneListFraction.value = value.coerceIn(0.20f, 0.50f)
    }

    private val _activeRailTab = MutableStateFlow(initialActiveRailTab)
    override val activeRailTab: StateFlow<RailTab> =
        _activeRailTab.asStateFlow()
    override fun setActiveRailTab(tab: RailTab) {
        _activeRailTab.value = tab
    }

    // Test-only mutator. The interface intentionally has no setter for
    // [railVisibility] — production mutation goes through
    // SettingsSyncImpl.setRailItemVisibility which writes the Room
    // table directly. This non-override helper lets unit tests exercise
    // the read-flow without standing up a full DAO.
    private val _railVisibility = MutableStateFlow(initialRailVisibility)
    override val railVisibility: StateFlow<Map<RailItem, Boolean>> =
        _railVisibility.asStateFlow()
    fun setRailVisibility(item: RailItem, visible: Boolean) {
        _railVisibility.value = _railVisibility.value.toMutableMap().apply {
            if (visible) remove(item) else this[item] = false
        }
    }

    private val _smartSearchPreferred = MutableStateFlow(initialSmartSearchPreferred)
    override val smartSearchPreferred: StateFlow<Boolean> =
        _smartSearchPreferred.asStateFlow()
    override fun setSmartSearchPreferred(preferred: Boolean) {
        _smartSearchPreferred.value = preferred
    }

    private val _railItemOrder = MutableStateFlow(sanitizeRailItemOrder(initialRailItemOrder))
    override val railItemOrder: StateFlow<List<RailItem>> = _railItemOrder.asStateFlow()
    override fun setRailItemOrder(items: List<RailItem>) {
        _railItemOrder.value = sanitizeRailItemOrder(items)
    }

    private val _powerFeaturesEnabled = MutableStateFlow(false)
    override val powerFeaturesEnabled: StateFlow<Boolean> = _powerFeaturesEnabled.asStateFlow()
    override fun setPowerFeaturesEnabled(enabled: Boolean) {
        _powerFeaturesEnabled.value = enabled
    }
}
