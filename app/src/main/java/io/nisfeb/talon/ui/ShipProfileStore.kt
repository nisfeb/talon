package io.nisfeb.talon.ui

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Cache of each logged-in ship's own display nickname, keyed by patp.
 * Populated as we see the active ship's `%contacts /v1/self` row;
 * consumed by the ship switcher drawer + the header label so users
 * don't only see raw patps there.
 *
 * Lives in shared (non-per-ship) prefs so it can show nicks for every
 * logged-in ship regardless of which one is currently active.
 */
class ShipProfileStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("talon.ship_profiles", Context.MODE_PRIVATE)

    private val _nicknames = MutableStateFlow(snapshot())
    val nicknames: StateFlow<Map<String, String>> = _nicknames.asStateFlow()

    fun setNickname(ship: String, nickname: String?) {
        val current = prefs.getString(ship, null)
        if (current == nickname || (current == null && nickname.isNullOrBlank())) return
        prefs.edit {
            if (nickname.isNullOrBlank()) remove(ship) else putString(ship, nickname)
        }
        _nicknames.value = snapshot()
    }

    fun nickname(ship: String): String? = prefs.getString(ship, null)

    private fun snapshot(): Map<String, String> = prefs.all
        .mapNotNull { (k, v) -> (v as? String)?.let { k to it } }
        .toMap()
}
