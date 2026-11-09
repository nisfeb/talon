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
     * Tell the relay what this device's app understands ([caps]), so it
     * may send those pushes. Its own route, not a field on /register: a
     * relay from before refuses a registration with a field it does not
     * know, and answers this 404, which is harmless. False on any failure.
     */
    suspend fun declareCaps(deviceId: String, caps: List<String>): Boolean = withContext(ioDispatcher) {
        if (deviceId.isBlank()) return@withContext false
        runCatching {
            val resp = http.post("${endpoint().trimEnd('/')}/devices/$deviceId/caps") {
                contentType(ContentType.Application.Json)
                setBody(JSON.encodeToString(CapsRequest(caps)))
            }
            resp.status.isSuccess()
        }.getOrDefault(false)
    }

    @Serializable
    private data class CapsRequest(val caps: List<String>)

    /**
     * Tell the relay this device's push endpoint changed (an iPhone's
     * alert token arriving after it registered), without the +code: the
     * device id is the app's own secret, as for caps. A relay from before
     * answers 404. False on any failure.
     */
    suspend fun updateEndpoint(deviceId: String, pushEndpoint: String): Boolean = withContext(ioDispatcher) {
        if (deviceId.isBlank() || pushEndpoint.isBlank()) return@withContext false
        runCatching {
            val resp = http.post("${endpoint().trimEnd('/')}/devices/$deviceId/endpoint") {
                contentType(ContentType.Application.Json)
                setBody(JSON.encodeToString(EndpointRequest(pushEndpoint)))
            }
            resp.status.isSuccess()
        }.getOrDefault(false)
    }

    @Serializable
    private data class EndpointRequest(val pushEndpoint: String)

    /**
     * Give an iPhone's tokens ("<voip>|<alert>") to the relay's APNs
     * gateway, which its own ship pushes through. With [kept], the tokens
     * behind that handle are replaced; null back means the gateway no
     * longer knows it (mint again). Throws on any other refusal or no
     * answer, so the move tries again later.
     */
    suspend fun gatewayEnroll(token: String, kept: GatewayDevice? = null): GatewayDevice? = withContext(ioDispatcher) {
        val resp = http.post("${endpoint().trimEnd('/')}/gateway/devices") {
            contentType(ContentType.Application.Json)
            setBody(JSON.encodeToString(GatewayEnrollRequest(token, kept?.handle, kept?.secret)))
        }
        when {
            resp.status.isSuccess() -> JSON.decodeFromString<GatewayDevice>(resp.bodyAsText())
            kept != null && resp.status.value in setOf(401, 404) -> null
            else -> error("the relay's gateway answered ${resp.status.value}")
        }
    }

    /** The app-icon count for a relay iPhone, null for badges off
     *  ([reportBadge]). False on any failure. */
    suspend fun setBadge(deviceId: String, count: Int?): Boolean = withContext(ioDispatcher) {
        runCatching {
            http.post("${endpoint().trimEnd('/')}/devices/$deviceId/badge") {
                contentType(ContentType.Application.Json)
                setBody(JSON.encodeToString(BadgeRequest(count)))
            }.status.isSuccess()
        }.getOrDefault(false)
    }

    /** The same for an iPhone its ship pushes to, by its gateway handle. */
    suspend fun gatewayBadge(device: GatewayDevice, count: Int?): Boolean = withContext(ioDispatcher) {
        runCatching {
            http.post("${endpoint().trimEnd('/')}/gateway/badge") {
                contentType(ContentType.Application.Json)
                setBody(JSON.encodeToString(GatewayBadgeRequest(device.handle, device.secret, count)))
            }.status.isSuccess()
        }.getOrDefault(false)
    }

    @Serializable
    private data class BadgeRequest(val count: Int?)

    @Serializable
    private data class GatewayBadgeRequest(val handle: String, val secret: String, val count: Int?)

    @Serializable
    private data class GatewayEnrollRequest(val token: String, val handle: String? = null, val secret: String? = null)

    /**
     * Tell the relay to forget this device entirely. Idempotent —
     * a 404 is fine because "already gone" is the goal. [reason] is one
     * of the REASON_ words, for the relay's log: a device that moved to
     * its ship's own push looked, from the relay, like a lost
     * registration (a user's report, 2026-10-08).
     */
    suspend fun unregister(deviceId: String, reason: String? = null): Boolean = withContext(ioDispatcher) {
        if (deviceId.isBlank()) return@withContext true
        runCatching {
            val resp = http.delete("${endpoint().trimEnd('/')}/devices/$deviceId" + (reason?.let { "?reason=$it" } ?: ""))
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

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /** Moved to the ship's own push (%trunk). */
        const val REASON_SHIP_PUSH = "ship-push"
        /** The owner turned the relay off for this ship. */
        const val REASON_OFF = "off"
        /** The ship was forgotten on this device. */
        const val REASON_FORGOTTEN = "forgotten"
    }
}

/** An iPhone's handle on the relay's APNs gateway, and the secret its
 *  ship pushes with. */
@Serializable
data class GatewayDevice(val handle: String, val secret: String)

