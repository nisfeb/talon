package io.nisfeb.talon.notify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
    /** This device's push endpoint, or null. */
    val endpoint: suspend () -> String?,
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
    caps: List<String>,
    timeoutMs: Long = 60_000,
): ShipPushMove = try {
    if (settings.shipPushDeclined(ship)) {
        ShipPushMove.NotWanted
    } else if (ports.trunkWire() < TrunkPush.WIRE) {
        ShipPushMove.NotSupported
    } else {
        val endpoint = ports.endpoint()
        if (endpoint == null) {
            ShipPushMove.NoEndpoint
        } else {
            val id = settings.trunkDeviceIdFor(ship).ifBlank { ports.newId().also { settings.setTrunkDeviceIdFor(ship, it) } }
            ports.poke(TrunkPush.register(id, endpoint, caps))
            val moved = settings.viaShipPush(ship) || run {
                val nonce = ports.newId()
                ports.poke(TrunkPush.test(id, nonce))
                ports.awaitNonce(nonce, timeoutMs)
            }
            if (!moved) {
                runCatching { ports.poke(TrunkPush.unregister(id)) }
                ShipPushMove.NotVerified
            } else {
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

