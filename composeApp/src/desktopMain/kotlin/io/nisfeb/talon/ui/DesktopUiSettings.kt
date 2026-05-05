package io.nisfeb.talon.ui

import io.nisfeb.talon.util.AppDirs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * JSON-file-backed UI settings for desktop. Sits next to the other
 * per-process settings files in the user-data dir. Atomic-move write
 * so a JVM crash mid-write can't truncate the file to an unparseable
 * state.
 */
class DesktopUiSettings(
    private val file: File = File(AppDirs.userData, "ui.json"),
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
        // Fraction of total width given to the chat-list pane on wide windows.
        val chatPaneListFraction: Float = 0.30f,
        val activeRailTab: String = RailTab.Chats.name,
    )

    private val initial = loadInitial()
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

    private val _groupChannelOrder = MutableStateFlow(
        runCatching { GroupChannelOrder.valueOf(initial.groupChannelOrder) }
            .getOrDefault(GroupChannelOrder.Recent),
    )
    override val groupChannelOrder: StateFlow<GroupChannelOrder> =
        _groupChannelOrder.asStateFlow()

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

    private fun persistCurrent() {
        val accent = _accentSettings.value
        persist(
            Persisted(
                hideComposerButtons = _hideComposerButtons.value,
                accentEnabled = accent.enabled,
                accentMode = accent.mode.name,
                accentCustomHex = accent.customHex,
                groupChannelOrder = _groupChannelOrder.value.name,
                chatPaneListFraction = _chatPaneListFraction.value,
                activeRailTab = _activeRailTab.value.name,
            ),
        )
    }

    private fun loadInitial(): Persisted {
        if (!file.exists()) return Persisted()
        return runCatching {
            JSON.decodeFromString<Persisted>(file.readText())
        }.getOrElse { Persisted() }
    }

    private fun persist(value: Persisted) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSON.encodeToString(value))
        Files.move(
            tmp.toPath(), file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private companion object {
        private val JSON = Json { ignoreUnknownKeys = true; prettyPrint = false }
    }
}
