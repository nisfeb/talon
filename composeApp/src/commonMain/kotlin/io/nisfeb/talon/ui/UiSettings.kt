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
}

class InMemoryUiSettings(initialHide: Boolean = false) : UiSettings {
    private val _hideComposerButtons = MutableStateFlow(initialHide)
    override val hideComposerButtons: StateFlow<Boolean> =
        _hideComposerButtons.asStateFlow()
    override fun setHideComposerButtons(hidden: Boolean) {
        _hideComposerButtons.value = hidden
    }
}
