package io.nisfeb.talon.notify

import io.nisfeb.talon.util.AppDirs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * JSON-file-backed relay state for desktop. Sits next to the other
 * per-process settings files. Atomic-move write so a JVM crash
 * mid-write can't truncate the file to junk.
 */
class DesktopRelaySettings(
    private val file: File = File(AppDirs.userData, "relay.json"),
) : RelaySettings {

    @Serializable
    private data class Persisted(
        val endpoint: String = RelaySettings.DEFAULT_ENDPOINT,
        val deviceIds: Map<String, String> = emptyMap(),
    )

    private val initial = loadInitial()
    private val _endpoint = MutableStateFlow(initial.endpoint)
    override val endpoint: StateFlow<String> = _endpoint.asStateFlow()
    private val deviceIds: MutableMap<String, String> =
        initial.deviceIds.toMutableMap()

    override fun setEndpoint(url: String) {
        if (_endpoint.value == url) return
        _endpoint.value = url
        persistCurrent()
    }

    override fun deviceIdFor(patp: String): String = deviceIds[patp].orEmpty()

    override fun setDeviceIdFor(patp: String, deviceId: String) {
        if (deviceIds[patp] == deviceId) return
        deviceIds[patp] = deviceId
        persistCurrent()
    }

    override fun clearDeviceIdFor(patp: String) {
        if (deviceIds.remove(patp) == null) return
        persistCurrent()
    }

    private fun persistCurrent() {
        persist(Persisted(endpoint = _endpoint.value, deviceIds = deviceIds.toMap()))
    }

    private fun loadInitial(): Persisted {
        if (!file.exists()) return Persisted()
        return runCatching {
            JSON.decodeFromString<Persisted>(file.readText())
        }.getOrElse { Persisted() }
    }

    private fun persist(value: Persisted) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSON.encodeToString(value))
        Files.move(
            tmp.toPath(), file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private companion object {
        private val JSON = Json { ignoreUnknownKeys = true; prettyPrint = false }
    }
}
