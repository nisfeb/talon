package io.nisfeb.talon.notify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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
    private val http: OkHttpClient,
    private val endpoint: () -> String,
) {

    @Serializable
    private data class RegisterRequest(
        val platform: String,
        val pushToken: String,
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
     * [existingDeviceId] is empty on first registration and the
     * deviceId returned by the relay is the device's identity from
     * then on. Pass it back on re-registration (e.g. when the FCM
     * token rotates) to avoid creating a new row.
     *
     * Returns the device id assigned by the relay, or null on any
     * failure (network, 4xx, 5xx, malformed response). The caller
     * surfaces a generic error to the user — we don't leak relay-
     * side specifics.
     */
    suspend fun register(
        platform: String,
        pushToken: String,
        existingDeviceId: String,
        shipUrl: String,
        patp: String,
        code: String,
    ): String? = withContext(Dispatchers.IO) {
        val body = JSON.encodeToString(
            RegisterRequest(
                platform = platform,
                pushToken = pushToken,
                deviceId = existingDeviceId,
                shipUrl = shipUrl,
                patp = patp,
                code = code,
            ),
        )
        val req = Request.Builder()
            .url("${endpoint().trimEnd('/')}/register")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val parsed = JSON.decodeFromString<RegisterResponse>(
                    resp.body?.string().orEmpty(),
                )
                if (parsed.ok && parsed.deviceId.isNotBlank()) parsed.deviceId
                else null
            }
        }.getOrNull()
    }

    /**
     * Tell the relay to forget this device entirely. Stops every
     * SSE connection the relay was holding for this id. Idempotent —
     * a 404 is fine because "already gone" is the goal.
     */
    suspend fun unregister(deviceId: String): Boolean = withContext(Dispatchers.IO) {
        if (deviceId.isBlank()) return@withContext true
        val req = Request.Builder()
            .url("${endpoint().trimEnd('/')}/devices/$deviceId")
            .delete()
            .build()
        runCatching {
            http.newCall(req).execute().use { resp ->
                resp.isSuccessful || resp.code == 404
            }
        }.getOrDefault(false)
    }

    /**
     * Health check: returns the count of ships the relay is tracking
     * for [deviceId]. Settings → Notification Health uses this for
     * the "is the relay seeing my ship?" indicator. null on any
     * failure (so the panel can render "unreachable" without
     * needing to disambiguate which step failed).
     */
    suspend fun health(deviceId: String): HealthResponse? = withContext(Dispatchers.IO) {
        if (deviceId.isBlank()) return@withContext null
        val req = Request.Builder()
            .url("${endpoint().trimEnd('/')}/health/$deviceId")
            .get()
            .build()
        runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                JSON.decodeFromString<HealthResponse>(resp.body?.string().orEmpty())
            }
        }.getOrNull()
    }

    private companion object {
        private val JSON_MEDIA = "application/json".toMediaType()
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
