package io.nisfeb.talon.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SharedPreferences-backed [MenuSeenStore] for Android. Per-ship
 * prefs file so a ship-switch shows that ship's own seen-state
 * (digests are per-ship; invites and statuses come from the ship's
 * %contacts and %groups). Mirrors the [AndroidDraftStore] pattern.
 */
class AndroidMenuSeenStore(context: Context, ship: String) : MenuSeenStore {
    private val prefsName: String = "talon.menuseen." +
        ship.removePrefix("~").replace(Regex("[^a-z0-9-]"), "_")
    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(snapshot())
    override val state: StateFlow<MenuSeenState> = _state.asStateFlow()

    override fun markDigestSeen(dateLocal: String?) {
        prefs.edit { if (dateLocal == null) remove(KEY_DIGEST) else putString(KEY_DIGEST, dateLocal) }
        _state.value = _state.value.copy(lastSeenDigestDate = dateLocal)
    }

    override fun markStatusesSeenAt(ms: Long) {
        prefs.edit { putLong(KEY_STATUSES, ms) }
        _state.value = _state.value.copy(lastSeenStatusesMs = ms)
    }

    override fun markInvitesSeen(snapshot: String) {
        prefs.edit { putString(KEY_INVITES, snapshot) }
        _state.value = _state.value.copy(lastSeenInvitesSnapshot = snapshot)
    }

    private fun snapshot(): MenuSeenState = MenuSeenState(
        lastSeenDigestDate = prefs.getString(KEY_DIGEST, null),
        lastSeenStatusesMs = prefs.getLong(KEY_STATUSES, 0L),
        lastSeenInvitesSnapshot = prefs.getString(KEY_INVITES, "") ?: "",
    )

    private companion object {
        private const val KEY_DIGEST = "lastSeenDigestDate"
        private const val KEY_STATUSES = "lastSeenStatusesMs"
        private const val KEY_INVITES = "lastSeenInvitesSnapshot"
    }
}
