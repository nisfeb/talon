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

    /** This device's id on [patp]'s own %trunk, minted here, "" before
     *  any ([moveToShipPush]). Not the relay's id. */
    fun trunkDeviceIdFor(patp: String): String = ""
    fun setTrunkDeviceIdFor(patp: String, id: String) {}

    /** Notifications for [patp] come from the ship's own %trunk, its
     *  test push having arrived; the public relay is not used for it. */
    fun viaShipPush(patp: String): Boolean = false
    fun setViaShipPush(patp: String, via: Boolean) {}

    /** The owner chose the public relay over the ship's own pushes for
     *  [patp] on this device: Talon does not move it to the ship again. */
    fun shipPushDeclined(patp: String): Boolean = false
    fun setShipPushDeclined(patp: String, declined: Boolean) {}

    /** An iPhone's handle on the relay's APNs gateway, for [patp]'s ship
     *  to push through ([gatewayRegistration]). */
    fun gatewayFor(patp: String): GatewayDevice? = null
    fun setGatewayFor(patp: String, device: GatewayDevice) {}

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
    private val trunkIds = mutableMapOf<String, String>()
    override fun trunkDeviceIdFor(patp: String): String = trunkIds[patp].orEmpty()
    override fun setTrunkDeviceIdFor(patp: String, id: String) { trunkIds[patp] = id }
    private val viaShip = mutableSetOf<String>()
    override fun viaShipPush(patp: String): Boolean = patp in viaShip
    override fun setViaShipPush(patp: String, via: Boolean) { if (via) viaShip += patp else viaShip -= patp }
    private val shipDeclined = mutableSetOf<String>()
    override fun shipPushDeclined(patp: String): Boolean = patp in shipDeclined
    override fun setShipPushDeclined(patp: String, declined: Boolean) { if (declined) shipDeclined += patp else shipDeclined -= patp }
    private val gateways = mutableMapOf<String, GatewayDevice>()
    override fun gatewayFor(patp: String): GatewayDevice? = gateways[patp]
    override fun setGatewayFor(patp: String, device: GatewayDevice) { gateways[patp] = device }
}
