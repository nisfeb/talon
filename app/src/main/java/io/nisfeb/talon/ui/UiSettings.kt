package io.nisfeb.talon.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UI-only preferences. Non-sensitive, so regular SharedPreferences —
 * no need for encryption. Backs simple composer-level toggles.
 */
class UiSettings(context: Context) {
    private val prefs = context.getSharedPreferences("talon.ui", Context.MODE_PRIVATE)

    private val _hideComposerButtons = MutableStateFlow(
        prefs.getBoolean(KEY_HIDE_COMPOSER_BUTTONS, false)
    )
    val hideComposerButtons: StateFlow<Boolean> = _hideComposerButtons.asStateFlow()

    fun setHideComposerButtons(hidden: Boolean) {
        prefs.edit().putBoolean(KEY_HIDE_COMPOSER_BUTTONS, hidden).apply()
        _hideComposerButtons.value = hidden
    }

    companion object {
        private const val KEY_HIDE_COMPOSER_BUTTONS = "hide_composer_buttons"
    }
}
