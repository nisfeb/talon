package io.nisfeb.talon.ui

import io.nisfeb.talon.ai.WatchwordsSyncSettings
import io.nisfeb.talon.notify.RelaySettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Small settings kept as JSON in a [UiSettingsStore] (a file on desktop,
 * Application Support on iOS). They lived in desktop code only, so iOS
 * fell back to in-memory copies and forgot all three on every launch: the
 * relay's endpoint and registration, the watchword-sync switch, and which
 * drawer dots had been seen.
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
    )

    private val initial = store.load(Persisted())
    private val _endpoint = MutableStateFlow(initial.endpoint)
    override val endpoint: StateFlow<String> = _endpoint.asStateFlow()
    private val deviceIds = initial.deviceIds.toMutableMap()

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
        if (deviceIds.remove(patp) == null) return
        save()
    }

    private fun save() = store.write(JSON.encodeToString(Persisted(_endpoint.value, deviceIds.toMap())))
}

/** The "mirror watchwords to %settings" switch; on until turned off. */
class StoredWatchwordsSyncSettings(private val store: UiSettingsStore) : WatchwordsSyncSettings {
    @Serializable
    private data class Persisted(val enabled: Boolean = true)

    private val _enabled = MutableStateFlow(store.load(Persisted()).enabled)
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    override fun setEnabled(value: Boolean) {
        if (_enabled.value == value) return
        store.write(JSON.encodeToString(Persisted(value)))
        _enabled.value = value
    }
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
