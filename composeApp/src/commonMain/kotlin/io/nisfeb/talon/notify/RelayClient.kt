package io.nisfeb.talon.notify

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.nisfeb.talon.util.ioDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Client for the Talon notification push relay (see relay/README.md).
 *
 * Talks to whatever URL the user configured in
 * [RelaySettings.endpoint] — defaults to the Talon-operated host
 * but self-hosters can point at their own. The relay's HTTP API is
 * stable and small (3 endpoints), so this client is a thin
 * stateless HTTP wrapper, not a connection-managing thing.
 */
class RelayClient(
    private val http: HttpClient,
    private val endpoint: () -> String,
) {

    @Serializable
    private data class RegisterRequest(
        val platform: String,
        /** UnifiedPush distributor endpoint URL the local
         *  distributor (ntfy / NextPush / …) handed the device.
         *  Treated as opaque on the wire. */
        val pushEndpoint: String,
        val deviceId: String,
        val shipUrl: String,
        val patp: String,
        val code: String,
    )

    @Serializable
    private data class RegisterResponse(
        val deviceId: String = "",
        val ok: Boolean = false,
        val error: String? = null,
    )

    @Serializable
    data class HealthResponse(
        val ok: Boolean = false,
        val ships: Int = 0,
        val message: String? = null,
    )

    /**
     * Register this device + ship pair with the relay. The +code is
     * forwarded to the relay over TLS; the relay logs in to derive
     * a urbauth cookie, encrypts that with its master secret, and
     * forgets the +code. See `relay/README.md` § Trust model.
     *
     * Returns the device id assigned by the relay, or null on any
     * failure (network, 4xx, 5xx, malformed response).
     */
    suspend fun register(
        platform: String,
        pushEndpoint: String,
        existingDeviceId: String,
        shipUrl: String,
        patp: String,
        code: String,
    ): String? = withContext(ioDispatcher) {
        val body = JSON.encodeToString(
            RegisterRequest(
                platform = platform,
                pushEndpoint = pushEndpoint,
                deviceId = existingDeviceId,
                shipUrl = shipUrl,
                patp = patp,
                code = code,
            ),
        )
        runCatching {
            val resp = http.post("${endpoint().trimEnd('/')}/register") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            if (!resp.status.isSuccess()) return@withContext null
            val parsed = JSON.decodeFromString<RegisterResponse>(resp.bodyAsText())
            if (parsed.ok && parsed.deviceId.isNotBlank()) parsed.deviceId else null
        }.getOrNull()
    }

    /**
     * Tell the relay to forget this device entirely. Idempotent —
     * a 404 is fine because "already gone" is the goal.
     */
    suspend fun unregister(deviceId: String): Boolean = withContext(ioDispatcher) {
        if (deviceId.isBlank()) return@withContext true
        runCatching {
            val resp = http.delete("${endpoint().trimEnd('/')}/devices/$deviceId")
            resp.status.isSuccess() || resp.status.value == 404
        }.getOrDefault(false)
    }

    /**
     * Health check: returns the count of ships the relay is tracking
     * for [deviceId]. null on any failure.
     */
    suspend fun health(deviceId: String): HealthResponse? = withContext(ioDispatcher) {
        if (deviceId.isBlank()) return@withContext null
        runCatching {
            val resp = http.get("${endpoint().trimEnd('/')}/health/$deviceId")
            if (!resp.status.isSuccess()) return@withContext null
            JSON.decodeFromString<HealthResponse>(resp.bodyAsText())
        }.getOrNull()
    }

    private companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
