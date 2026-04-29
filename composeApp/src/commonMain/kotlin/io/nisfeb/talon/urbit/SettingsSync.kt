package io.nisfeb.talon.urbit

import kotlinx.serialization.json.JsonObject

/**
 * TEMPORARY DUPLICATE: commonMain interface mirroring the surface of
 * the production [SettingsSync] class at
 * app/src/main/java/io/nisfeb/talon/urbit/SettingsSync.kt that
 * [TlonChatRepo] depends on internally.
 *
 * The production class lives in app/ because it pulls in
 * Android-only knobs ([io.nisfeb.talon.ai.AiSettings] +
 * [io.nisfeb.talon.ai.DailyDigestSettings] are SharedPreferences-backed)
 * and is constructed by [io.nisfeb.talon.TalonApplication].
 *
 * commonMain TlonChatRepo takes a `SettingsSync?` constructor parameter:
 * - on Android via composeApp Wave 2+, the host can supply the
 *   production class (it implements this interface — see Stage F
 *   wiring) or a stub.
 * - on desktop, the early port passes `null` and the relevant code
 *   paths short-circuit. Settings stay local until a desktop %settings
 *   bridge is wired.
 *
 * UI callers (37 in production) reach pushAiSettings / pushWatchwords
 * / etc. directly on the production class — those methods live there
 * and do not appear here. This interface is intentionally minimal:
 * it covers just what commonMain TlonChatRepo's session loop pokes.
 */
interface SettingsSync {
    fun attach(channel: UrbitChannel)
    suspend fun bootstrap()
    suspend fun applySettingsEvent(payload: JsonObject)

    // ───────── home-list reorder hooks ─────────
    // Called from DmListScreen drag callbacks. The Android impl writes
    // through to Room and (for the push variants) sends pokes to
    // %settings on the ship; the desktop default is a no-op.

    suspend fun reorderGroupOrdersLocal(flags: List<String>) {}
    suspend fun pushGroupOrders() {}
    suspend fun reorderFolderMembersLocal(folderId: Long, whoms: List<String>) {}
    suspend fun pushFolderMembersOrder(folderId: Long) {}

    // ───────── bookmark mutations ─────────
    // Adds / removes a bookmark entry on the ship's %settings agent.
    // On desktop / null-settingsSync builds the action sheet hides the
    // button entirely, so these are never called in that path.

    suspend fun addBookmark(whom: String, postId: String, ts: Long) {}
    suspend fun removeBookmark(whom: String, postId: String) {}

    // ───────── notify level mutation ─────────
    // Persists the per-conversation notification volume locally and
    // pushes to the ship's %settings agent. `level` is one of the
    // string constants in [io.nisfeb.talon.data.NotifyLevel].

    suspend fun setNotifyLevel(whom: String, level: String) {}

    // ───────── watchwords mutations ─────────
    // Toggles whether a chat is excluded from watchword scanning. The
    // Android impl routes through Watchwords.excludeChat so backfill /
    // %settings push fire correctly; desktop default is a no-op.

    suspend fun setWatchwordExclude(whom: String, excluded: Boolean) {}

    // ───────── folder mutations ─────────

    suspend fun createFolder(name: String, sortOrder: Int): Long = -1L
    suspend fun renameFolder(id: Long, name: String) {}
    suspend fun deleteFolder(id: Long) {}
    suspend fun addFolderMember(folderId: Long, whom: String) {}
    suspend fun addGroupToFolder(folderId: Long, groupFlag: String) {}
    suspend fun removeFolderMember(folderId: Long, whom: String) {}
    suspend fun removeGroupFromFolder(folderId: Long, groupFlag: String) {}

    // ───────── bookmark folder mutations ─────────
    // BookmarksScreen uses these to organize saved messages into
    // user-named buckets. Returns the new folder id from create.

    suspend fun createBookmarkFolder(name: String, sortOrder: Int = 0): Long = -1L
    suspend fun renameBookmarkFolder(id: Long, name: String) {}
    suspend fun deleteBookmarkFolder(id: Long) {}
    suspend fun addBookmarkToFolder(folderId: Long, whom: String, postId: String) {}
    suspend fun removeBookmarkFromFolder(folderId: Long, whom: String, postId: String) {}

    companion object {
        const val DESK = "talon"
    }
}
