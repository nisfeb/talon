package io.nisfeb.talon.notify

import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android implementation of [RelaySettings] — SharedPreferences-
 * backed. Endpoint URL is a single key; per-ship device ids are
 * stored under `device_id::<patp>` so the storage shape is stable
 * across ship list changes.
 */
class AndroidRelaySettings(context: Context) : RelaySettings {
    private val prefs = context.getSharedPreferences("talon.relay", Context.MODE_PRIVATE)

    override val changes: kotlinx.coroutines.flow.Flow<Unit> = kotlinx.coroutines.flow.callbackFlow {
        // Held here: SharedPreferences keeps its listeners weakly.
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private val _endpoint = MutableStateFlow(
        prefs.getString(KEY_ENDPOINT, null) ?: RelaySettings.DEFAULT_ENDPOINT,
    )
    override val endpoint: StateFlow<String> = _endpoint.asStateFlow()

    override fun setEndpoint(url: String) {
        if (_endpoint.value == url) return
        prefs.edit().putString(KEY_ENDPOINT, url).apply()
        _endpoint.value = url
    }

    override fun deviceIdFor(patp: String): String =
        prefs.getString(KEY_DEVICE_ID_PREFIX + patp, null).orEmpty()

    override fun setDeviceIdFor(patp: String, deviceId: String) {
        prefs.edit().putString(KEY_DEVICE_ID_PREFIX + patp, deviceId).apply()
    }

    override fun clearDeviceIdFor(patp: String) {
        prefs.edit().remove(KEY_DEVICE_ID_PREFIX + patp).apply()
    }

    override fun trunkDeviceIdFor(patp: String): String =
        prefs.getString(KEY_TRUNK_ID_PREFIX + patp, null).orEmpty()

    override fun setTrunkDeviceIdFor(patp: String, id: String) {
        prefs.edit().putString(KEY_TRUNK_ID_PREFIX + patp, id).apply()
    }

    override fun viaShipPush(patp: String): Boolean = prefs.getBoolean(KEY_VIA_SHIP_PREFIX + patp, false)

    override fun setViaShipPush(patp: String, via: Boolean) {
        prefs.edit().putBoolean(KEY_VIA_SHIP_PREFIX + patp, via).apply()
    }

    override fun shipPushDeclined(patp: String): Boolean = prefs.getBoolean(KEY_SHIP_DECLINED_PREFIX + patp, false)

    override fun setShipPushDeclined(patp: String, declined: Boolean) {
        prefs.edit().putBoolean(KEY_SHIP_DECLINED_PREFIX + patp, declined).apply()
    }

    private companion object {
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_TRUNK_ID_PREFIX = "trunk_device_id::"
        private const val KEY_VIA_SHIP_PREFIX = "via_ship::"
        private const val KEY_SHIP_DECLINED_PREFIX = "ship_push_declined::"
        private const val KEY_DEVICE_ID_PREFIX = "device_id::"
    }
}
