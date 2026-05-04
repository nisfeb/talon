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
}
