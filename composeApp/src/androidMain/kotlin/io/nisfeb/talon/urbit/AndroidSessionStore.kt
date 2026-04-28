package io.nisfeb.talon.urbit

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * SharedPreferences-backed [SessionStore] for Android.
 *
 * Persists the login state for every ship the user has authenticated
 * against and tracks which one is active. The previous (single-ship)
 * schema is auto-migrated into the new list on first load.
 *
 * Serialization is hand-rolled JSON (via kotlinx.serialization.json's
 * buildJsonObject/buildJsonArray helpers) rather than @Serializable
 * so we don't depend on the compiler-plugin's generated serializers —
 * those have been flaky under our R8 rules and this state is small
 * enough that manual code is clearer anyway.
 */
class AndroidSessionStore(context: Context) : SessionStore {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    init { migrateLegacyIfPresent() }

    /** Every saved ship, keyed by patp. */
    override fun all(): List<SavedSession> = readAll().values.sortedBy { it.ship }

    /** The currently-active ship's session, or null if none. */
    override fun active(): SavedSession? {
        val ship = prefs.getString(KEY_ACTIVE, null) ?: return null
        return readAll()[ship]
    }

    /** The active ship's patp, or null. */
    override fun activeShip(): String? = prefs.getString(KEY_ACTIVE, null)

    /** Persist or update a ship's session and (by default) make it active. */
    override fun save(entry: SavedSession, makeActive: Boolean) {
        val map = readAll().toMutableMap()
        map[entry.ship] = entry
        writeAll(map)
        if (makeActive) prefs.edit { putString(KEY_ACTIVE, entry.ship) }
    }

    /** Switch the active pointer. Silently no-ops if [ship] isn't stored. */
    override fun setActive(ship: String) {
        if (ship !in readAll().keys) return
        prefs.edit { putString(KEY_ACTIVE, ship) }
    }

    /** Remove a ship. If it was active, the next remaining ship
     *  becomes active — or none, if the list empties. */
    override fun remove(ship: String) {
        val map = readAll().toMutableMap()
        map.remove(ship)
        writeAll(map)
        if (prefs.getString(KEY_ACTIVE, null) == ship) {
            val next = map.keys.firstOrNull()
            prefs.edit {
                if (next == null) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, next)
            }
        }
    }

    /** Wipe everything. */
    override fun clearAll() = prefs.edit { clear() }

    // ───────── internals ─────────

    private fun readAll(): Map<String, SavedSession> {
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyMap()
        return runCatching {
            val arr = Json.parseToJsonElement(raw).jsonArray
            arr.mapNotNull { parseSession(it.jsonObject) }.associateBy { it.ship }
        }.getOrDefault(emptyMap())
    }

    private fun writeAll(map: Map<String, SavedSession>) {
        prefs.edit {
            if (map.isEmpty()) {
                remove(KEY_LIST)
            } else {
                val jsonText = buildJsonArray {
                    map.values.forEach { add(sessionToJson(it)) }
                }.toString()
                putString(KEY_LIST, jsonText)
            }
        }
    }

    private fun sessionToJson(s: SavedSession): JsonObject = buildJsonObject {
        put("shipUrl", s.shipUrl)
        put("ship", s.ship)
        put("cookieName", s.cookieName)
        put("cookieValue", s.cookieValue)
        put("cookieDomain", s.cookieDomain)
    }

    private fun parseSession(obj: JsonObject): SavedSession? {
        fun str(key: String): String? = obj[key].asStr()
        return SavedSession(
            shipUrl = str("shipUrl") ?: return null,
            ship = str("ship") ?: return null,
            cookieName = str("cookieName") ?: return null,
            cookieValue = str("cookieValue") ?: return null,
            cookieDomain = str("cookieDomain") ?: return null,
        )
    }

    /** Old schema stored one ship under discrete keys. Fold it in. */
    private fun migrateLegacyIfPresent() {
        if (prefs.contains(KEY_LIST)) return
        val url = prefs.getString(KEY_LEGACY_URL, null) ?: return
        val ship = prefs.getString(KEY_LEGACY_SHIP, null) ?: return
        val name = prefs.getString(KEY_LEGACY_COOKIE_NAME, null) ?: return
        val value = prefs.getString(KEY_LEGACY_COOKIE_VALUE, null) ?: return
        val domain = prefs.getString(KEY_LEGACY_COOKIE_DOMAIN, null) ?: return
        val saved = SavedSession(url, ship, name, value, domain)
        writeAll(mapOf(ship to saved))
        prefs.edit {
            putString(KEY_ACTIVE, ship)
            remove(KEY_LEGACY_URL)
            remove(KEY_LEGACY_SHIP)
            remove(KEY_LEGACY_COOKIE_NAME)
            remove(KEY_LEGACY_COOKIE_VALUE)
            remove(KEY_LEGACY_COOKIE_DOMAIN)
        }
    }

    companion object {
        private const val PREFS_NAME = "talon.session"
        private const val KEY_LIST = "sessions_v2"
        private const val KEY_ACTIVE = "active_ship"
        private const val KEY_LEGACY_URL = "ship_url"
        private const val KEY_LEGACY_SHIP = "ship"
        private const val KEY_LEGACY_COOKIE_NAME = "cookie_name"
        private const val KEY_LEGACY_COOKIE_VALUE = "cookie_value"
        private const val KEY_LEGACY_COOKIE_DOMAIN = "cookie_domain"
    }
}
