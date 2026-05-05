package io.nisfeb.talon.relay

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.slf4j.LoggerFactory

@Serializable
data class RegisterRequest(
    /** "android" or "desktop" — drives which push transport we use. */
    val platform: String,
    /** FCM device token (Android) or future APNS / desktop-webhook
     *  identifier. The relay treats it as opaque. */
    val pushToken: String,
    /** Device id minted by the client on first registration. Caller
     *  reuses it on re-registration to update the FCM token without
     *  re-creating the device row. Empty string = relay should mint
     *  one and return it in the response. */
    val deviceId: String,
    /** Ship base URL, e.g. "https://my-ship.example.com" or the LAN
     *  IP you point Talon at. */
    val shipUrl: String,
    /** Ship patp with leading ~. */
    val patp: String,
    /** Plaintext +code. The relay logs in to derive the urbauth
     *  cookie, then encrypts the cookie for storage. The +code
     *  itself is NOT persisted past the login call. */
    val code: String,
)

@Serializable
data class RegisterResponse(
    val deviceId: String,
    val ok: Boolean,
    val error: String? = null,
)

@Serializable
data class HealthResponse(
    val ok: Boolean,
    val ships: Int,
    val message: String? = null,
)

fun Application.installRoutes(
    db: Db,
    pool: ConnectionPool,
    masterSecret: String,
    httpClient: OkHttpClient,
) {
    val log = LoggerFactory.getLogger("Routes")
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            log.error("unhandled error: ${cause.message}", cause)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (cause.message ?: "error")))
        }
    }

    routing {
        get("/") {
            call.respond(mapOf("relay" to "talon", "status" to "ok"))
        }

        post("/register") {
            val req = call.receive<RegisterRequest>()
            // Validate shape before attempting login.
            if (!req.patp.startsWith("~") || req.shipUrl.isBlank() || req.code.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    RegisterResponse(deviceId = "", ok = false, error = "missing fields"),
                )
                return@post
            }
            // 1. Login to ship to derive the urbauth cookie.
            val cookie = loginToShip(httpClient, req.shipUrl, req.code) ?: run {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    RegisterResponse(deviceId = "", ok = false, error = "ship login failed"),
                )
                return@post
            }
            // 2. Mint or reuse the device id.
            val deviceId = req.deviceId.ifBlank { newDeviceId() }
            db.upsertDevice(deviceId, req.pushToken, req.platform)
            // 3. Encrypt + persist the cookie.
            val sealed = Crypto.seal(cookie, masterSecret)
            db.upsertShip(deviceId, req.patp, req.shipUrl, sealed)
            // 4. Spin up the SSE consumer.
            db.shipsForDevice(deviceId).firstOrNull { it.patp == req.patp }
                ?.let { pool.ensureRunning(it) }

            call.respond(RegisterResponse(deviceId = deviceId, ok = true))
        }

        delete("/devices/{deviceId}") {
            val id = call.parameters["deviceId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            for (row in db.shipsForDevice(id)) pool.stopRow(row.rowId)
            db.deleteDevice(id)
            call.respond(HttpStatusCode.NoContent)
        }

        get("/health/{deviceId}") {
            val id = call.parameters["deviceId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val ships = db.shipsForDevice(id)
            call.respond(HealthResponse(ok = true, ships = ships.size))
        }
    }
}

/**
 * POST `/~/login` with `password=<+code>` to mint a session cookie.
 * Returns the full Cookie header value to send back on subsequent
 * requests, or null on failure.
 */
private fun loginToShip(http: OkHttpClient, shipUrl: String, code: String): String? {
    val form = "password=${code.removePrefix("~")}"
    val req = Request.Builder()
        .url("$shipUrl/~/login")
        .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
        .build()
    return runCatching {
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            // Urbit returns Set-Cookie: urbauth-~ship=...; Path=/
            // We only need the name=value part.
            val raw = resp.headers("Set-Cookie")
                .firstOrNull { it.startsWith("urbauth-") } ?: return null
            raw.substringBefore(';').trim()
        }
    }.getOrNull()
}
