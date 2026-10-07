package io.nisfeb.talon.notify

/**
 * Putting this device on the relay, which watches the ship and pushes
 * to the device. On iOS it is the only way a notification arrives: the
 * app is suspended in the background, and nothing else wakes it. Every
 * iPhone but one had never registered (2026-10-06), because the one way
 * in was a technical Settings panel, so they got nothing at all.
 */
sealed interface Enrollment {
    /** On the relay. [alerts] false: calls ring, but messages do not
     *  alert until notifications are allowed for Talon. */
    data class On(val deviceId: String, val alerts: Boolean) : Enrollment
    /** No push token to give the relay; [why] in the owner's words. */
    data class NoToken(val why: String) : Enrollment
    /** The relay said no, or did not answer. */
    data class Refused(val why: String) : Enrollment
}

/**
 * Register this device for [ship] with its +[code]: the relay signs in
 * once, keeps an encrypted session and forgets the code. Kept: the
 * device id, the endpoint registered (so a token that changes is sent
 * again), and that the owner said yes.
 */
suspend fun enrollDevice(
    client: RelayClient,
    settings: RelaySettings,
    tokens: PushTokenProvider,
    ship: String,
    shipUrl: String,
    code: String,
): Enrollment {
    val endpoint = tokens.token() ?: return Enrollment.NoToken(tokens.missingTokenReason())
    val id = client.register(
        platform = tokens.platform,
        pushEndpoint = endpoint,
        existingDeviceId = settings.deviceIdFor(ship),
        shipUrl = shipUrl,
        patp = ship,
        code = code,
    ) ?: return Enrollment.Refused(
        "The relay could not sign in to your ship. Check the +code, and that the ship is reachable from the internet.",
    )
    settings.setDeviceIdFor(ship, id)
    settings.setRegisteredEndpointFor(ship, endpoint)
    settings.setDeclinedFor(ship, false)
    // What this device understands, so the relay may send it; an older
    // relay says 404, harmless.
    tokens.caps.takeIf { it.isNotEmpty() }?.let { client.declareCaps(id, it) }
    return Enrollment.On(id, alerts = tokens.alertsIn(endpoint))
}

/**
 * Send the relay this device's endpoint again where it changed since it
 * registered: an iPhone's alert token comes with the owner's yes to iOS's
 * prompt, which can come later, and the relay dropped every message to a
 * device registered without one. True when the relay has the current one.
 */
suspend fun refreshRegisteredEndpoint(
    client: RelayClient,
    settings: RelaySettings,
    tokens: PushTokenProvider,
    ship: String,
): Boolean {
    val id = settings.deviceIdFor(ship).takeIf { it.isNotBlank() } ?: return false
    val endpoint = tokens.token() ?: return false
    if (endpoint == settings.registeredEndpointFor(ship)) return true
    if (!client.updateEndpoint(id, endpoint)) return false
    settings.setRegisteredEndpointFor(ship, endpoint)
    return true
}

/**
 * Whether to ask, on a device that needs the relay ([needed]), about
 * notifications for [ship]: right after signing in, or at launch until
 * the owner says yes or not now. A device already on the relay is not asked.
 */
fun shouldOfferNotificationSetup(
    needed: Boolean,
    ship: String?,
    settings: RelaySettings,
    justSignedIn: Boolean,
): Boolean =
    needed && ship != null && settings.deviceIdFor(ship).isBlank() && !settings.viaShipPush(ship) &&
        (justSignedIn || !settings.declinedFor(ship))

/** Where an iPhone's notifications for a ship come from, as Settings says it. */
enum class PhoneNotifications { Ship, Relay, Off }

/** A phone moved to its ship's own pushes is off the relay, and still on. */
fun phoneNotifications(settings: RelaySettings, ship: String): PhoneNotifications = when {
    settings.viaShipPush(ship) -> PhoneNotifications.Ship
    settings.deviceIdFor(ship).isNotBlank() -> PhoneNotifications.Relay
    else -> PhoneNotifications.Off
}

/**
 * Whether Talon must keep itself running in the background to hear
 * [ship]: no push reaches this device for it (sneagan, 2026-10-07:
 * "talon's always on background service should turn off if there is an
 * appropriate push service enabled"). A push needs the device's push
 * endpoint ([pushEndpoint], null without a distributor) and someone
 * pushing to it: the ship's own %trunk, or the relay.
 *
 * ponytail: trusts the registrations; a relay that lost its session, or a
 * trunk that dropped the device, still reads as covered. Ask the ship's
 * push status (trunk wire 12) when that lands.
 */
fun keepAliveNeeded(
    settings: RelaySettings,
    ship: String,
    pushEndpoint: String?,
    /** Mail notifies only from the running app: auspex has no push path. */
    mailNeedsProcess: Boolean = false,
    /** The ship's %trunk stopped answering after the move ([ShipPushHealth]). */
    shipPushBroken: Boolean = false,
): Boolean =
    mailNeedsProcess || pushEndpoint.isNullOrBlank() ||
        (settings.viaShipPush(ship) && shipPushBroken) ||
        !(settings.viaShipPush(ship) || settings.deviceIdFor(ship).isNotBlank())

