package io.nisfeb.talon.notify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import io.ktor.client.request.delete
import io.ktor.client.request.put
import io.ktor.http.contentType
import io.ktor.http.ContentType
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pushes from the owner's own ship, through %trunk (gwbtc/trunk#1, wire
 * 11): the ship watches its own activity and pushes to its devices, so
 * no +code or session leaves it, as it does for the public relay. The
 * pokes, on mark trunk-action, owner-only.
 */
object TrunkPush {
    const val WIRE = 11
    const val MARK = "trunk-action"

    fun register(id: String, endpoint: String, caps: List<String>): JsonElement = buildJsonObject {
        put("push-register", buildJsonObject {
            put("id", id)
            put("platform", "unifiedpush")
            put("endpoint", endpoint)
            put("caps", buildJsonArray { caps.forEach { add(JsonPrimitive(it)) } })
        })
    }

    /** An iPhone: the ship pushes through the APNs gateway on the relay at
     *  [gateway], which holds the phone's tokens behind [handle]. */
    fun registerGateway(id: String, gateway: String, handle: String, secret: String, caps: List<String> = emptyList()): JsonElement =
        buildJsonObject {
            put("push-register", buildJsonObject {
                put("id", id)
                put("platform", "ios-gateway")
                put("gateway", gateway)
                put("handle", handle)
                put("secret", secret)
                put("caps", buildJsonArray { caps.forEach { add(JsonPrimitive(it)) } })
            })
        }

    /** What an iPhone tells its ship it understands (wire 12): notices,
     *  reads (its background clear), and the badge while the owner has it on. */
    fun iosCaps(badges: Boolean): List<String> = listOfNotNull("notice", "read", "badge".takeIf { badges })

    fun unregister(id: String): JsonElement = buildJsonObject { put("push-unregister", id) }

    fun test(id: String, nonce: String): JsonElement = buildJsonObject {
        put("push-test", buildJsonObject {
            put("id", id)
            put("nonce", nonce)
        })
    }
}

/**
 * The nonces of test pushes this process has received. A test push can
 * land before the migration starts waiting (trunk sends at once), so they
 * are kept, not only offered. Only the waiting process knows its nonce,
 * so a forged push proves nothing.
 */
object PushTestNonces {
    private val seen = MutableStateFlow<Set<String>>(emptySet())

    fun received(nonce: String) = seen.update { (it + nonce).let { s -> if (s.size > 16) s.drop(s.size - 16).toSet() else s } }

    suspend fun await(nonce: String, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { seen.first { nonce in it } } != null
}

/**
 * Ships whose %trunk stopped answering after this device moved to its
 * pushes (desk suspended, downgraded, uninstalled): nothing reaches the
 * device from them, so Android keeps Talon running to hear them itself
 * ([keepAliveNeeded]). Set and cleared by [moveToShipPush].
 */
object ShipPushHealth {
    private val _broken = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val broken: kotlinx.coroutines.flow.StateFlow<Set<String>> = _broken
    internal fun set(ship: String, isBroken: Boolean) = _broken.update { if (isBroken) it + ship else it - ship }
}

/** How a move to the ship's own pushes ended. */
sealed interface ShipPushMove {
    /** Pushes come from the ship now. */
    data object ViaShip : ShipPushMove
    /** The ship's %trunk is older than [TrunkPush.WIRE], or absent. */
    data object NotSupported : ShipPushMove
    /** The owner chose the public relay ([leaveShipPush]); not asked again. */
    data object NotWanted : ShipPushMove
    /** No push endpoint on this device to give the ship. */
    data object NoEndpoint : ShipPushMove
    /** The test push never came: the public relay stays. */
    data object NotVerified : ShipPushMove
    /** It could not be tried (no connection): the next start tries again. */
    data class Failed(val why: String) : ShipPushMove
}

/** What the move does, given from outside so it can be tested. */
class ShipPushPorts(
    /** The ship's %trunk wire, 0 for none. */
    val trunkWire: suspend () -> Int,
    /** A trunk-action poke to our own ship. */
    val poke: suspend (JsonElement) -> Unit,
    /** The push-register poke for this device under [id] (Android's
     *  endpoint, or an iPhone's gateway handle), or null for no endpoint. */
    val register: suspend (id: String) -> JsonElement?,
    val awaitNonce: suspend (nonce: String, timeoutMs: Long) -> Boolean = PushTestNonces::await,
    /** Take this device off the public relay; true when it is off. */
    val relayUnregister: suspend (deviceId: String) -> Boolean,
    val newId: () -> String,
)

/**
 * Move [ship]'s notifications on this device from the public relay to the
 * ship's own %trunk, only once a test push from the ship has arrived: a
 * setup that does not deliver must never leave the device with nothing.
 *
 * Once moved, every start registers again (an upsert, cheap; a ship whose
 * trunk lost its devices gets this one back) and takes the device off the
 * relay if an earlier try could not, or it would get every push twice.
 */
suspend fun moveToShipPush(
    ship: String,
    settings: RelaySettings,
    ports: ShipPushPorts,
    timeoutMs: Long = 60_000,
): ShipPushMove = try {
    if (settings.shipPushDeclined(ship)) {
        ShipPushMove.NotWanted
    } else if (ports.trunkWire() < TrunkPush.WIRE) {
        // Moved, and the ship no longer answers for it: say so, so the
        // device does not sit with neither the ship's pushes nor its own.
        ShipPushHealth.set(ship, settings.viaShipPush(ship))
        ShipPushMove.NotSupported
    } else {
        val id = settings.trunkDeviceIdFor(ship).ifBlank { ports.newId() }
        val register = ports.register(id)
        if (register == null) {
            ShipPushMove.NoEndpoint
        } else {
            settings.setTrunkDeviceIdFor(ship, id)
            val wasMoved = settings.viaShipPush(ship)
            ports.poke(register)
            val moved = wasMoved || try {
                val nonce = ports.newId()
                ports.poke(TrunkPush.test(id, nonce))
                ports.awaitNonce(nonce, timeoutMs)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Stopped midway (a ship switch, the app closing): the ship
                // would push here as well as the relay, every alert twice.
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    runCatching { ports.poke(TrunkPush.unregister(id)) }
                }
                throw e
            }
            if (!moved) {
                runCatching { ports.poke(TrunkPush.unregister(id)) }
                ShipPushMove.NotVerified
            } else {
                ShipPushHealth.set(ship, false)
                settings.setViaShipPush(ship, true)
                val relayId = settings.deviceIdFor(ship)
                if (relayId.isNotBlank() && ports.relayUnregister(relayId)) settings.clearDeviceIdFor(ship)
                ShipPushMove.ViaShip
            }
        }
    }
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    ShipPushMove.Failed(e.message ?: e.toString())
}

/**
 * Back to the public relay, by the owner's choice in Settings (sneagan:
 * manual only, nothing automatic): the ship forgets this device, and
 * Talon stops moving it to the ship. Registering with the relay again
 * takes the +code, as it always has. The ship's answer is not waited
 * on; the device is the owner's to leave.
 */
suspend fun leaveShipPush(ship: String, settings: RelaySettings, poke: suspend (JsonElement) -> Unit) {
    settings.setShipPushDeclined(ship, true)
    settings.setViaShipPush(ship, false)
    settings.trunkDeviceIdFor(ship).takeIf { it.isNotBlank() }?.let { id -> runCatching { poke(TrunkPush.unregister(id)) } }
}

/**
 * Try the move each time the ship's connection settles, and again when
 * [trunkArrived] says %trunk has just been installed there, until a try
 * settles it: moved, refused, or a test push that never came. A ship
 * without %trunk at sign-in gets it minutes later (Talon installs it),
 * and used to stay on the relay until the app next started, which on
 * Android, with Talon kept running, could be days.
 */
suspend fun keepMovingToShipPush(
    ship: String,
    settings: RelaySettings,
    ports: ShipPushPorts,
    bootstrapping: kotlinx.coroutines.flow.Flow<Boolean>,
    trunkArrived: kotlinx.coroutines.flow.Flow<Unit> = kotlinx.coroutines.flow.emptyFlow(),
    log: (ShipPushMove) -> Unit = {},
) {
    kotlinx.coroutines.flow.merge(bootstrapping.filter { !it }.map { }, trunkArrived).first {
        when (moveToShipPush(ship, settings, ports).also(log)) {
            ShipPushMove.ViaShip, ShipPushMove.NotWanted, ShipPushMove.NotVerified -> true
            else -> false
        }
    }
}

/**
 * Signing out of or forgetting [ship]: its %trunk stops pushing here and
 * the device forgets it ever moved. The pushes otherwise went on for a
 * ship no longer signed in, previews and rings included, and with the
 * ship's Settings row gone there was no way to stop them. [poke] is best
 * effort; the ship may be unreachable.
 */
suspend fun forgetShipPush(ship: String, settings: RelaySettings, poke: suspend (JsonElement) -> Unit) {
    val id = settings.trunkDeviceIdFor(ship)
    settings.setViaShipPush(ship, false)
    settings.setShipPushDeclined(ship, false)
    settings.setTrunkDeviceIdFor(ship, "")
    ShipPushHealth.set(ship, false)
    if (id.isNotBlank()) runCatching { kotlinx.coroutines.withTimeout(15_000) { poke(TrunkPush.unregister(id)) } }
}

/**
 * A trunk-action poke to [saved]'s ship with its saved cookie, for when
 * the app's own connection to it is gone (signing out). One channel,
 * deleted after.
 */
suspend fun pokeTrunkSignedOut(saved: io.nisfeb.talon.urbit.SavedSession, body: JsonElement, http: io.ktor.client.HttpClient? = null) {
    val client = http ?: io.nisfeb.talon.util.createAppHttpClient()
    val channel = "${saved.shipUrl.trimEnd('/')}/~/channel/talon-out-${io.nisfeb.talon.data.newGid()}"
    val cookie = "${saved.cookieName}=${saved.cookieValue}"
    val payload = kotlinx.serialization.json.buildJsonArray {
        add(buildJsonObject {
            put("id", 1)
            put("action", "poke")
            put("ship", saved.ship.removePrefix("~"))
            put("app", io.nisfeb.talon.call.TrunkWire.AGENT)
            put("mark", TrunkPush.MARK)
            put("json", body)
        })
    }
    try {
        val resp = client.put(channel) {
            contentType(ContentType.Application.Json)
            header("Cookie", cookie)
            setBody(payload.toString())
        }
        check(resp.status.value in 200..299) { "the ship answered ${resp.status.value}" }
    } finally {
        runCatching { client.delete(channel) { header("Cookie", cookie) } }
        if (http == null) client.close()
    }
}

/**
 * An iPhone's registration (sneagan: "can iOS switch too and just use my
 * relay for the apple requirement"): its tokens go to the relay's APNs
 * gateway, which only signs and sends for Apple, and the ship gets the
 * handle. The tokens behind a kept handle are replaced in place; a handle
 * the gateway lost is minted again. Null without an alert token: the
 * owner has not allowed notifications, so nothing would show.
 */
suspend fun gatewayRegistration(
    ship: String,
    id: String,
    settings: RelaySettings,
    relay: RelayClient,
    tokens: PushTokenProvider,
    caps: List<String> = emptyList(),
): JsonElement? {
    val token = tokens.token()?.takeIf { tokens.alertsIn(it) } ?: return null
    val dev = settings.gatewayFor(ship)?.let { relay.gatewayEnroll(token, it) }
        ?: checkNotNull(relay.gatewayEnroll(token)) { "the gateway gave no device" }
    settings.setGatewayFor(ship, dev)
    return TrunkPush.registerGateway(id, settings.endpoint.value.trimEnd('/'), dev.handle, dev.secret, caps)
}

