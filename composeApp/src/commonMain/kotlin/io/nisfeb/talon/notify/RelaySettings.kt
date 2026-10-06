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

    /** The push endpoint last registered for [patp], "" where unknown: a
     *  token that changes since is sent again ([refreshRegisteredEndpoint]). */
    fun registeredEndpointFor(patp: String): String = ""
    fun setRegisteredEndpointFor(patp: String, endpoint: String) {}

    /** The owner said not now to notifications for [patp] on this device:
     *  not asked again at launch. Settings still turns them on. */
    fun declinedFor(patp: String): Boolean = false
    fun setDeclinedFor(patp: String, declined: Boolean) {}

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

    private val endpoints = mutableMapOf<String, String>()
    override fun registeredEndpointFor(patp: String): String = endpoints[patp].orEmpty()
    override fun setRegisteredEndpointFor(patp: String, endpoint: String) { endpoints[patp] = endpoint }
    private val declined = mutableSetOf<String>()
    override fun declinedFor(patp: String): Boolean = patp in declined
    override fun setDeclinedFor(patp: String, declined: Boolean) { if (declined) this.declined += patp else this.declined -= patp }
}
