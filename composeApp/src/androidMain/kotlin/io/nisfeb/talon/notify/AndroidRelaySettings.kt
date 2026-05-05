package io.nisfeb.talon.notify

import android.content.Context
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

    private companion object {
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_DEVICE_ID_PREFIX = "device_id::"
    }
}
