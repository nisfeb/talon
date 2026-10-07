package io.nisfeb.talon.relay

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

@Serializable
data class GatewayEnroll(val token: String, val handle: String? = null, val secret: String? = null)

@Serializable
data class GatewayDevice(val handle: String, val secret: String)

@Serializable
data class GatewayBadge(val handle: String, val secret: String, val count: Int? = null)

@Serializable
data class GatewayPush(
    val handle: String,
    val secret: String,
    val kind: String,
    val patp: String = "",
    val whom: String = "",
    val postId: String = "",
    val title: String = "",
    val body: String = "",
    val parent: String? = null,
    val nonce: String? = null,
    /** A voip push's body: Push.kt's ring or ring-cancel object. */
    val payload: JsonObject? = null,
    /** The app-icon count the ship counted (trunk wire 12, the "badge"
     *  cap): on an alert, and the whole of a "badge" push. */
    val badge: Int? = null,
    /** "notice" for a push from another app on the ship (wire 12). */
    val event: String? = null,
    /** A notice's target in the app, as the sending agent gave it. */
    val open: kotlinx.serialization.json.JsonElement? = null,
)

/**
 * The Apple hop for an iPhone whose own ship pushes to it (%trunk wire
 * 11, gwbtc/trunk#1). APNs wants HTTP/2 and an ES256-signed token, which
 * a ship's iris cannot make, so the ship hands each push here and this
 * sends it on. Nothing more: no +code, no session, no stream. The phone's
 * tokens sit behind a handle and a secret that only the phone and its
 * ship know; the secret is kept as its SHA-256.
 *
 * Answers, as agreed with trunk: 404 or 410 for a dead device (trunk
 * drops it), 401 for a wrong secret (dropped too), 409 for a push the
 * device has no token for, 502 for any other APNs refusal.
 *
 * ponytail: enrolling is open, as it must be before a phone has anything
 * to show; a row is ~200 bytes. Add a per-address limit if it is abused.
 */
class Gateway(
    private val db: Db,
    private val alert: (token: String, push: GatewayPush, badge: Int?) -> ApnsResult,
    private val voip: (token: String, payload: String) -> ApnsResult,
    /** Only the icon's number: nothing shown (wire 12 "badge"). */
    private val badgeOnly: (token: String, badge: Int) -> ApnsResult = { _, _ -> ApnsResult(0, "unsupported") },
    /** A background push the app wakes for (wire 12 "clear"). */
    private val background: (token: String, payload: String) -> ApnsResult = { _, _ -> ApnsResult(0, "unsupported") },
) {
    private val log = LoggerFactory.getLogger("Gateway")
    private val rings = GatewayRings()

    /** Mint a handle for [GatewayEnroll.token], or replace the tokens
     *  behind a known one. The status, and the device on success. */
    fun enroll(req: GatewayEnroll): Pair<Int, GatewayDevice?> {
        if (!TOKENS.matches(req.token) || req.token == "|") return 400 to null
        if (req.handle == null) {
            val dev = GatewayDevice(random(18), random(32))
            db.putGatewayDevice(dev.handle, sha256(dev.secret), req.token)
            return 200 to dev
        }
        val row = db.gatewayDevice(req.handle) ?: return 404 to null
        val secret = req.secret.orEmpty()
        if (!matches(row, secret)) return 401 to null
        db.putGatewayDevice(req.handle, row.secretSha256, req.token)
        return 200 to GatewayDevice(req.handle, secret)
    }

    fun push(req: GatewayPush): Int {
        val row = db.gatewayDevice(req.handle) ?: return 404
        if (!matches(row, req.secret)) return 401
        val result = when (req.kind) {
            "alert" -> {
                val fields = listOf(req.patp, req.whom, req.postId, req.title, req.body, req.parent.orEmpty(), req.nonce.orEmpty(), req.event.orEmpty())
                if (fields.any { it.length > MAX_FIELD } || (req.open?.toString()?.length ?: 0) > MAX_VOIP) return 400
                if (req.badge != null && req.badge !in 0..MAX_BADGE) return 400
                val token = Push.iosAlertToken(row.endpoint)
                    ?: return 409.also { log.warn("gateway ${req.handle.take(6)}…: an alert, and the phone gave no alert token") }
                // The ship's own count when it sends one (wire 12); else this
                // gateway's (a wire 11 ship). A test alert is nothing to count.
                val badge = req.badge ?: if (req.nonce == null) db.nextBadge(Db.GATEWAY, req.handle) else null
                alert(token, req, badge)
            }
            "badge" -> {
                val n = req.badge ?: return 400
                if (n !in 0..MAX_BADGE) return 400
                val token = Push.iosAlertToken(row.endpoint)
                    ?: return 409.also { log.warn("gateway ${req.handle.take(6)}…: a badge, and the phone gave no alert token") }
                badgeOnly(token, n)
            }
            "clear" -> {
                if (req.whom.isBlank() || listOf(req.patp, req.whom).any { it.length > MAX_FIELD }) return 400
                val token = Push.iosAlertToken(row.endpoint)
                    ?: return 409.also { log.warn("gateway ${req.handle.take(6)}…: a clear, and the phone gave no alert token") }
                background(token, clearPayload(req.patp, req.whom))
            }
            "voip" -> {
                val payload = req.payload?.toString() ?: return 400
                if (payload.length > MAX_VOIP) return 400
                if (!rings.shouldSend(req.handle, req.payload)) return 200
                val token = Push.iosVoipToken(row.endpoint)
                    ?: return 409.also { log.warn("gateway ${req.handle.take(6)}…: a ring, and the phone gave no VoIP token") }
                voip(token, payload)
            }
            else -> return 400
        }
        return when {
            result.code in 200..299 -> 200
            result.code == 410 || result.reason in DEAD -> 410
            else -> 502
        }
    }

    /** The phone's own count, from the app while it is open; null for
     *  badges off. 204 when set. */
    fun badge(req: GatewayBadge): Int {
        val row = db.gatewayDevice(req.handle) ?: return 404
        if (!matches(row, req.secret)) return 401
        if (req.count != null && req.count !in 0..MAX_BADGE) return 400
        db.setBadge(Db.GATEWAY, req.handle, req.count)
        return 204
    }

    private fun matches(row: Db.GatewayRow, secret: String) =
        MessageDigest.isEqual(sha256(secret).toByteArray(), row.secretSha256.toByteArray())

    private companion object {
        /** "<voip>|<alert>", either half empty, as the iOS app gives it. */
        val TOKENS = Regex("[0-9a-fA-F]{0,200}\\|[0-9a-fA-F]{0,200}")
        /** APNs refusals that mean the token will never work. */
        val DEAD = setOf("BadDeviceToken", "Unregistered", "DeviceTokenNotForTopic")
        const val MAX_FIELD = 1000
        const val MAX_VOIP = 3000
        const val MAX_BADGE = 99_999
        val rng = SecureRandom()

        fun random(bytes: Int): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(rng::nextBytes))

        fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/**
 * The calls each phone was rung for, so a cancel goes only to a phone that
 * rang. The ship's %trunk cancels on every device it has (gwbtc/trunk#1),
 * and on an iPhone a VoIP push must report a call: a cancel for a ring it
 * never got showed as a missed call in its Recents. A cancel for a ring
 * this gateway never sent to that handle is answered 200 and not sent.
 *
 * ponytail: in memory, for [keepMs] (an answered call's hangup can come
 * hours later). After a relay restart a cancel for an earlier ring is not
 * sent: a ringing phone then stops on its own 45 s timeout, and a live
 * call's hangup still reaches the app over its own stream. Persist it if
 * that bites.
 */
internal class GatewayRings(
    private val keepMs: Long = 4 * 60 * 60 * 1000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val rung = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun shouldSend(handle: String, payload: JsonObject): Boolean {
        val event = (payload["event"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        val id = (payload["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return true
        val t = now()
        rung.entries.removeIf { t - it.value > keepMs }
        return when (event) {
            "ring" -> { rung["$handle $id"] = t; true }
            "ring-cancel" -> rung.containsKey("$handle $id")
            else -> true
        }
    }
}

/** A read, as a background push: the app wakes and removes the chat's
 *  delivered notifications. Background pushes carry no alert, sound or
 *  badge; the count comes in its own "badge" push. */
internal fun clearPayload(patp: String, whom: String): String =
    "{\"aps\":{\"content-available\":1},\"event\":\"read\",\"patp\":\"${jsonEscape(patp)}\",\"whom\":\"${jsonEscape(whom)}\"}"

