package io.nisfeb.talon.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * SharedPreferences-backed [DraftStore] for Android. Per-ship prefs
 * file so ship-switch hides the other ship's drafts. Mirrors the
 * production `class DraftStore(context, ship)` in app/.
 */
class AndroidDraftStore(context: Context, ship: String) : DraftStore() {
    // Per-ship prefs file so switching ships hides the other ship's
    // drafts. The filename can't contain `~` or `/`, so we sanitize.
    private val prefsName: String = "talon.drafts." +
        ship.removePrefix("~").replace(Regex("[^a-z0-9-]"), "_")
    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    init {
        backing.value = snapshot()
    }

    // Hold a strong reference so the listener isn't GC'd
    // (SharedPreferences stores it via WeakReference).
    @Suppress("unused")
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        backing.value = snapshot()
    }.also(prefs::registerOnSharedPreferenceChangeListener)

    override fun load(whom: String): String = prefs.getString(whom, "") ?: ""

    override fun save(whom: String, draft: String) {
        prefs.edit {
            if (draft.isBlank()) remove(whom) else putString(whom, draft)
        }
        // Refresh backing ourselves rather than waiting on the change
        // listener. edit{} updates the in-memory prefs synchronously, so
        // snapshot() already reflects the write; the listener is async and
        // can miss callbacks, which left the conversation list advertising
        // "Draft:" after a send had already cleared it.
        backing.value = snapshot()
    }

    override fun clear(whom: String) {
        prefs.edit { remove(whom) }
        backing.value = snapshot()
    }

    private fun snapshot(): Map<String, String> = prefs.all
        .mapNotNull { (k, v) ->
            val s = v as? String ?: return@mapNotNull null
            if (s.isBlank()) null else k to s
        }
        .toMap()
}
