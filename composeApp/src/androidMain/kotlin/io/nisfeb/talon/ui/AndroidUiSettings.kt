package io.nisfeb.talon.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android implementation of [UiSettings] — SharedPreferences-backed.
 * Mirrors the production `class UiSettings(context)` in app/ that
 * this replaces post-Stage-F. Desktop counterpart is the JSON-file
 * [DesktopUiSettings] in desktopMain.
 */
class AndroidUiSettings(context: Context) : UiSettings {
    private val prefs = context.getSharedPreferences("talon.ui", Context.MODE_PRIVATE)

    private val _hideComposerButtons = MutableStateFlow(
        prefs.getBoolean(KEY_HIDE_COMPOSER_BUTTONS, false),
    )
    override val hideComposerButtons: StateFlow<Boolean> =
        _hideComposerButtons.asStateFlow()

    override fun setHideComposerButtons(hidden: Boolean) {
        if (_hideComposerButtons.value == hidden) return
        prefs.edit().putBoolean(KEY_HIDE_COMPOSER_BUTTONS, hidden).apply()
        _hideComposerButtons.value = hidden
    }

    private companion object {
        private const val KEY_HIDE_COMPOSER_BUTTONS = "hide_composer_buttons"
    }
}
