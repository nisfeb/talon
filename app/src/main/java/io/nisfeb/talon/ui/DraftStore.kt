package io.nisfeb.talon.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SharedPreferences-backed store for in-progress composer text, keyed
 * by conversation `whom`. Exposes the full draft map as a StateFlow so
 * the DM list can show per-conversation "Draft: …" previews without
 * re-reading SharedPreferences on every row render.
 */
class DraftStore(context: Context, ship: String) {
    // Per-ship prefs file so switching ships hides the other ship's
    // drafts. The filename can't contain `~` or `/`, so we sanitize.
    private val prefsName: String = "talon.drafts." +
        ship.removePrefix("~").replace(Regex("[^a-z0-9-]"), "_")
    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(snapshot())

    /** Reactive map of whom → draft. Empty drafts are absent. */
    val state: StateFlow<Map<String, String>> = _state.asStateFlow()

    // Hold a strong reference so the listener isn't GC'd (SharedPreferences
    // stores it via WeakReference).
    @Suppress("unused")
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _state.value = snapshot()
    }.also(prefs::registerOnSharedPreferenceChangeListener)

    fun load(whom: String): String = prefs.getString(whom, "") ?: ""

    fun save(whom: String, draft: String) {
        prefs.edit {
            if (draft.isBlank()) remove(whom) else putString(whom, draft)
        }
    }

    fun clear(whom: String) {
        prefs.edit { remove(whom) }
    }

    private fun snapshot(): Map<String, String> = prefs.all
        .mapNotNull { (k, v) ->
            val s = v as? String ?: return@mapNotNull null
            if (s.isBlank()) null else k to s
        }
        .toMap()
}
