package io.nisfeb.talon.notify

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SharedPreferences-backed [LastOpenChatStore]. Each ship (patp) gets
 * one preference key — `whom:<patp> → <whom>`. Avoids the full-map
 * read/write cycle of the JSON impl while preserving the same
 * external behaviour.
 *
 * Reads on construction populate the StateFlow snapshot so the
 * scaffold can seed its right-pane on first composition without a
 * suspending call.
 */
class AndroidLastOpenChatStore(context: Context) : LastOpenChatStore {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(snapshot())
    override val state: StateFlow<Map<String, String>> = _state.asStateFlow()

    override fun set(patp: String, whom: String) {
        if (_state.value[patp] == whom) return
        prefs.edit().putString(KEY_PREFIX + patp, whom).apply()
        _state.value = _state.value + (patp to whom)
    }

    override fun clear(patp: String) {
        if (patp !in _state.value) return
        prefs.edit().remove(KEY_PREFIX + patp).apply()
        _state.value = _state.value.minus(patp)
    }

    private fun snapshot(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for ((key, value) in prefs.all) {
            if (key.startsWith(KEY_PREFIX) && value is String) {
                out[key.removePrefix(KEY_PREFIX)] = value
            }
        }
        return out
    }

    private companion object {
        private const val PREFS_NAME = "talon.last_open_chat"
        private const val KEY_PREFIX = "whom:"
    }
}
