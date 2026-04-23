package io.nisfeb.talon.urbit

import android.content.Context
import androidx.core.content.edit

/**
 * Persists just enough login state to re-authenticate on app restart:
 * the ship URL, the ship patp, and the urbauth cookie.
 *
 * Cookies are plain SharedPreferences (not EncryptedSharedPreferences)
 * for v1 — we rely on Android's per-app sandbox. Swap to encrypted
 * storage before shipping to the Play Store.
 */
class SessionStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Saved(
        val shipUrl: String,
        val ship: String,      // "~patp"
        val cookieName: String, // e.g. "urbauth-~patp"
        val cookieValue: String,
        val cookieDomain: String,
    )

    fun save(entry: Saved) {
        prefs.edit {
            putString(KEY_URL, entry.shipUrl)
            putString(KEY_SHIP, entry.ship)
            putString(KEY_COOKIE_NAME, entry.cookieName)
            putString(KEY_COOKIE_VALUE, entry.cookieValue)
            putString(KEY_COOKIE_DOMAIN, entry.cookieDomain)
        }
    }

    fun load(): Saved? {
        val url = prefs.getString(KEY_URL, null) ?: return null
        val ship = prefs.getString(KEY_SHIP, null) ?: return null
        val name = prefs.getString(KEY_COOKIE_NAME, null) ?: return null
        val value = prefs.getString(KEY_COOKIE_VALUE, null) ?: return null
        val domain = prefs.getString(KEY_COOKIE_DOMAIN, null) ?: return null
        return Saved(url, ship, name, value, domain)
    }

    fun clear() = prefs.edit { clear() }

    companion object {
        private const val PREFS_NAME = "talon.session"
        private const val KEY_URL = "ship_url"
        private const val KEY_SHIP = "ship"
        private const val KEY_COOKIE_NAME = "cookie_name"
        private const val KEY_COOKIE_VALUE = "cookie_value"
        private const val KEY_COOKIE_DOMAIN = "cookie_domain"
    }
}
