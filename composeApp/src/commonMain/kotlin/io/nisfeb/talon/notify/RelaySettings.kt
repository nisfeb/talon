package io.nisfeb.talon.notify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistent relay-registration state. Two pieces:
 *   - The endpoint URL — global, applies to every ship. Default is
 *     the Talon-operated host; self-hosters override.
 *   - The per-ship device id minted at registration. Reused on
 *     re-registration so an FCM token rotation doesn't make the
 *     relay forget the ship.
 *
 * Per-platform impls back to SharedPreferences (Android), atomic
 * JSON file (Desktop), or in-memory (tests).
 */
interface RelaySettings {
    /**
     * Relay endpoint base URL, no trailing slash. The default is
     * the Talon-operated relay; self-hosters change it via the
     * Settings → Notification Health "Endpoint" field.
     */
    val endpoint: StateFlow<String>
    fun setEndpoint(url: String)

    /**
     * Device id the relay assigned on the most recent successful
     * /register call for [patp]. Empty string means "this ship has
     * never been registered with the relay (or was unregistered)."
     */
    fun deviceIdFor(patp: String): String
    fun setDeviceIdFor(patp: String, deviceId: String)
    fun clearDeviceIdFor(patp: String)

    companion object {
        const val DEFAULT_ENDPOINT = "https://relay.nisfeb.com"
    }
}

class InMemoryRelaySettings(
    initialEndpoint: String = RelaySettings.DEFAULT_ENDPOINT,
) : RelaySettings {
    private val _endpoint = MutableStateFlow(initialEndpoint)
    override val endpoint: StateFlow<String> = _endpoint.asStateFlow()
    override fun setEndpoint(url: String) {
        _endpoint.value = url
    }

    private val deviceIds = mutableMapOf<String, String>()
    override fun deviceIdFor(patp: String): String = deviceIds[patp].orEmpty()
    override fun setDeviceIdFor(patp: String, deviceId: String) {
        deviceIds[patp] = deviceId
    }
    override fun clearDeviceIdFor(patp: String) {
        deviceIds.remove(patp)
    }
}
