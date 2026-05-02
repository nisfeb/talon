package io.nisfeb.talon.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Snapshot of "the user has seen X up to here" for the More-menu's
 * freshness-dot logic. Persisted per-ship by the platform leaf so the
 * dots don't flicker back to "fresh" on every relaunch — leaving the
 * brief pip on 24/7 helps no one.
 *
 * Fields are deliberately simple values (String / Long) rather than
 * sets, so the persistence layer stays a flat JSON-ish key-value
 * shape and a future migration is free.
 */
data class MenuSeenState(
    /** dateLocal ("yyyy-MM-dd") of the digest the user has acknowledged.
     *  Null until they open Today's brief at least once. */
    val lastSeenDigestDate: String? = null,
    /** unix-ms of the last time the user opened Statuses. Status-feed
     *  rows newer than this surface a fresh-content dot. */
    val lastSeenStatusesMs: Long = 0L,
    /** Opaque hash of the invite set the user last saw. The current
     *  invite set hashed the same way; mismatch + non-empty current
     *  set drives the dot. Empty default surfaces a dot for any
     *  pending invite the first time the screen renders. */
    val lastSeenInvitesSnapshot: String = "",
)

/**
 * Per-ship persistence of [MenuSeenState]. Per the cross-platform
 * discipline in CLAUDE.md, this is an `interface` in commonMain with a
 * Noop default, plus per-leaf impls (SharedPreferences on Android,
 * a JSON file on desktop). Tests get the in-memory default.
 */
interface MenuSeenStore {
    val state: StateFlow<MenuSeenState>
    fun markDigestSeen(dateLocal: String?)
    fun markStatusesSeenAt(ms: Long)
    fun markInvitesSeen(snapshot: String)
}

/**
 * In-memory fallback. Used by tests and any leaf that hasn't wired a
 * persistent backend yet. State resets on process restart.
 */
class InMemoryMenuSeenStore : MenuSeenStore {
    private val _state = MutableStateFlow(MenuSeenState())
    override val state: StateFlow<MenuSeenState> = _state.asStateFlow()
    override fun markDigestSeen(dateLocal: String?) {
        _state.value = _state.value.copy(lastSeenDigestDate = dateLocal)
    }
    override fun markStatusesSeenAt(ms: Long) {
        _state.value = _state.value.copy(lastSeenStatusesMs = ms)
    }
    override fun markInvitesSeen(snapshot: String) {
        _state.value = _state.value.copy(lastSeenInvitesSnapshot = snapshot)
    }
}

/** Drop-in for tests / fresh leaves. */
val NoopMenuSeenStore: MenuSeenStore = InMemoryMenuSeenStore()

/**
 * Stable fingerprint for the "is this invite set the same one the
 * user already saw" comparison. Sorts the flags so reorderings don't
 * register as a change; keeps this in commonMain so both stores
 * compare against an identical canonical form.
 */
fun invitesSnapshot(flags: Iterable<String>): String =
    flags.toSortedSet().joinToString(",")
