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

    companion object {
        const val DESK = "talon"
    }
}
