package io.nisfeb.talon.ui

import io.nisfeb.talon.notify.RelaySettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Small settings kept as JSON in a [UiSettingsStore] (a file on desktop,
 * Application Support on iOS). They lived in desktop code only, so iOS
 * fell back to in-memory copies and forgot both on every launch: the
 * relay's endpoint and registration, and which drawer dots had been seen.
 */

private val JSON = Json { ignoreUnknownKeys = true }

private inline fun <reified T> UiSettingsStore.load(default: T): T =
    read()?.let { runCatching { JSON.decodeFromString<T>(it) }.getOrNull() } ?: default

/** Relay endpoint and per-ship device ids. */
class StoredRelaySettings(private val store: UiSettingsStore) : RelaySettings {
    @Serializable
    private data class Persisted(
        val endpoint: String = RelaySettings.DEFAULT_ENDPOINT,
        val deviceIds: Map<String, String> = emptyMap(),
        val registered: Map<String, String> = emptyMap(),
        val declined: Set<String> = emptySet(),
        val trunkIds: Map<String, String> = emptyMap(),
        val viaShip: Set<String> = emptySet(),
        val shipDeclined: Set<String> = emptySet(),
    )

    private val initial = store.load(Persisted())
    private val _endpoint = MutableStateFlow(initial.endpoint)
    override val endpoint: StateFlow<String> = _endpoint.asStateFlow()
    private val deviceIds = initial.deviceIds.toMutableMap()
    private val registered = initial.registered.toMutableMap()
    private val declined = initial.declined.toMutableSet()
    private val trunkIds = initial.trunkIds.toMutableMap()
    private val viaShip = initial.viaShip.toMutableSet()
    private val shipDeclined = initial.shipDeclined.toMutableSet()

    override fun setEndpoint(url: String) {
        if (_endpoint.value == url) return
        _endpoint.value = url
        save()
    }

    override fun deviceIdFor(patp: String): String = deviceIds[patp].orEmpty()

    override fun setDeviceIdFor(patp: String, deviceId: String) {
        if (deviceIds[patp] == deviceId) return
        deviceIds[patp] = deviceId
        save()
    }

    override fun clearDeviceIdFor(patp: String) {
        val had = deviceIds.remove(patp) != null
        if (registered.remove(patp) == null && !had) return
        save()
    }

    override fun registeredEndpointFor(patp: String): String = registered[patp].orEmpty()

    override fun setRegisteredEndpointFor(patp: String, endpoint: String) {
        if (registered[patp] == endpoint) return
        registered[patp] = endpoint
        save()
    }

    override fun declinedFor(patp: String): Boolean = patp in declined

    override fun setDeclinedFor(patp: String, declined: Boolean) {
        if ((patp in this.declined) == declined) return
        if (declined) this.declined += patp else this.declined -= patp
        save()
    }

    override fun trunkDeviceIdFor(patp: String): String = trunkIds[patp].orEmpty()

    override fun setTrunkDeviceIdFor(patp: String, id: String) {
        if (trunkIds[patp] == id) return
        trunkIds[patp] = id
        save()
    }

    override fun viaShipPush(patp: String): Boolean = patp in viaShip

    override fun setViaShipPush(patp: String, via: Boolean) {
        if ((patp in viaShip) == via) return
        if (via) viaShip += patp else viaShip -= patp
        save()
    }

    override fun shipPushDeclined(patp: String): Boolean = patp in shipDeclined

    override fun setShipPushDeclined(patp: String, declined: Boolean) {
        if ((patp in shipDeclined) == declined) return
        if (declined) shipDeclined += patp else shipDeclined -= patp
        save()
    }

    private fun save() = store.write(
        JSON.encodeToString(
            Persisted(_endpoint.value, deviceIds.toMap(), registered.toMap(), declined.toSet(), trunkIds.toMap(), viaShip.toSet(), shipDeclined.toSet()),
        ),
    )
}

/** One ship's [MenuSeenState]. */
class StoredMenuSeenStore(private val store: UiSettingsStore) : MenuSeenStore {
    @Serializable
    private data class Persisted(val lastSeenStatusesMs: Long = 0L, val lastSeenInvitesSnapshot: String = "")

    private val _state = MutableStateFlow(store.load(Persisted()).let { MenuSeenState(it.lastSeenStatusesMs, it.lastSeenInvitesSnapshot) })
    override val state: StateFlow<MenuSeenState> = _state.asStateFlow()

    override fun markStatusesSeenAt(ms: Long) = set(_state.value.copy(lastSeenStatusesMs = ms))
    override fun markInvitesSeen(snapshot: String) = set(_state.value.copy(lastSeenInvitesSnapshot = snapshot))

    private fun set(next: MenuSeenState) {
        _state.value = next
        store.write(JSON.encodeToString(Persisted(next.lastSeenStatusesMs, next.lastSeenInvitesSnapshot)))
    }
}

/** The file name a ship's [StoredMenuSeenStore] is kept under. */
fun menuSeenFileName(ship: String): String =
    "menuseen-" + ship.removePrefix("~").replace(Regex("[^a-z0-9-]"), "_") + ".json"
