package io.nisfeb.talon.relay

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * APNs VoIP push, for native incoming-call ringing on iOS.
 *
 * A VoIP push (apns-push-type: voip, priority 10) is delivered to the
 * device's PushKit token even when the app is killed, and iOS wakes
 * the app to report the call to CallKit. This is the only Apple push
 * class that rings a backgrounded phone like a real call — and Apple
 * requires that every one results in a CallKit report, which the iOS
 * app does.
 *
 * Token auth (a .p8 key, not a certificate): a short-lived ES256 JWT
 * signed with the team's APNs auth key. The same key signs VoIP and
 * alert pushes, so no separate VoIP Services certificate is needed.
 * The JWT is cached and refreshed well inside APNs's 1-hour ceiling —
 * minting one per request trips TooManyProviderTokenUpdates.
 *
 * No JWT library: the token is three base64url parts and one
 * SHA256withECDSA signature, all in the JDK. Adding a dependency to
 * the relay's hot path to save a dozen lines is not worth it.
 */
class Apns(
    private val teamId: String,
    private val keyId: String,
    /** The .p8 file contents (PEM). */
    p8Pem: String,
    /** The app's bundle id; the VoIP topic is "<bundleId>.voip". */
    private val bundleId: String,
    /** Production APNs (api.push.apple.com) vs sandbox. TestFlight and
     *  the App Store are production; a development build is sandbox. */
    production: Boolean,
) {
    private val log = LoggerFactory.getLogger("Apns")

    private val host =
        if (production) "https://api.push.apple.com" else "https://api.sandbox.push.apple.com"
    private val topic = "$bundleId.voip"

    private val privateKey = run {
        // Strip PEM armor lines generically (any "-----…-----"), then
        // whitespace — avoids embedding a key-marker literal in source.
        val body = p8Pem
            .replace(Regex("-----[A-Z ]+-----"), "")
            .replace(Regex("\\s"), "")
        val der = Base64.getDecoder().decode(body)
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der))
    }

    // OkHttp negotiates HTTP/2 over TLS ALPN on its own, which APNs
    // requires. A VoIP push is worthless once the caller has given up,
    // so the timeouts are tight.
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private data class CachedJwt(val value: String, val mintedAtMs: Long)
    private val cachedJwt = AtomicReference<CachedJwt?>(null)

    /** Push [payload] (a JSON body the iOS app parses to report the
     *  call to CallKit) to [voipToken], a hex PushKit token. */
    fun sendVoip(voipToken: String, payload: String, expirationSecs: Int = 60): ApnsResult {
        val req = Request.Builder()
            .url("$host/3/device/$voipToken")
            .header("authorization", "bearer ${jwt()}")
            .header("apns-topic", topic)
            .header("apns-push-type", "voip")
            .header("apns-priority", "10")
            .header(
                "apns-expiration",
                (System.currentTimeMillis() / 1000 + expirationSecs).toString(),
            )
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        // 410 Gone = the token is dead; the device must re-register. The
        // reason is logged and returned; the DB is not touched from here.
        return execute(req, "voip", voipToken)
    }

    /**
     * A user-visible alert (apns-push-type: alert, on the app's own
     * topic). Unlike a VoIP push it needs no app code to display, so a
     * killed app still shows it; the payload carries the same fields
     * the Android data push does so a tap can open the chat.
     */
    fun sendAlert(
        token: String,
        title: String,
        body: String,
        patp: String,
        whom: String,
        postId: String,
        expirationSecs: Int = 24 * 3600,
        /** The thread a reply is in, so a tap opens it. */
        parent: String? = null,
        /** A ship's test push ([Gateway]): the app takes it as proof. */
        nonce: String? = null,
        /** The app-icon count, when its owner has badges on. */
        badge: Int? = null,
    ): ApnsResult {
        val payload = alertPayload(title, body, patp, whom, postId, parent, nonce, badge)
        val req = Request.Builder()
            .url("$host/3/device/$token")
            .header("authorization", "bearer ${jwt()}")
            .header("apns-topic", bundleId)
            .header("apns-push-type", "alert")
            .header("apns-priority", "10")
            .apply { if (whom.isNotBlank()) header("apns-collapse-id", whom.take(64)) }
            .header(
                "apns-expiration",
                (System.currentTimeMillis() / 1000 + expirationSecs).toString(),
            )
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        return execute(req, "alert", token)
    }

    private fun execute(req: Request, kind: String, token: String): ApnsResult = try {
        http.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) {
                ApnsResult(resp.code, "")
            } else {
                val text = resp.body.string().take(200)
                log.warn("apns $kind HTTP ${resp.code} → ${token.take(12)}… $text")
                ApnsResult(resp.code, REASON.find(text)?.groupValues?.get(1).orEmpty())
            }
        }
    } catch (e: Throwable) {
        log.warn("apns $kind failed → ${token.take(12)}…: ${e.message}")
        ApnsResult(0, e.message.orEmpty())
    }


    /** A cached bearer JWT, refreshed every [JWT_REFRESH_MS]. */
    private fun jwt(): String {
        val now = System.currentTimeMillis()
        cachedJwt.get()?.let { if (now - it.mintedAtMs < JWT_REFRESH_MS) return it.value }
        val fresh = mintJwt(now / 1000)
        cachedJwt.set(CachedJwt(fresh, now))
        return fresh
    }

    internal fun mintJwt(iatSecs: Long): String {
        val header = b64url("""{"alg":"ES256","kid":"$keyId"}""".toByteArray())
        val claims = b64url("""{"iss":"$teamId","iat":$iatSecs}""".toByteArray())
        val signingInput = "$header.$claims"
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(signingInput.toByteArray())
            sign()
        }
        return "$signingInput.${b64url(derToJose(der))}"
    }

    private fun b64url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /**
     * ECDSA signatures come out of the JDK as a DER SEQUENCE of two
     * INTEGERs (r, s); JOSE/ES256 wants the raw 64-byte r||s, each
     * left-padded to 32. Without this conversion APNs rejects the
     * token as InvalidProviderToken.
     */
    private fun derToJose(der: ByteArray): ByteArray {
        // SEQUENCE (0x30) len; INTEGER (0x02) rLen r; INTEGER (0x02) sLen s
        var i = 2
        if (der[1].toInt() and 0x80 != 0) i += der[1].toInt() and 0x7f // long-form seq len
        require(der[i].toInt() == 0x02) { "bad DER: expected INTEGER for r" }
        val rLen = der[i + 1].toInt()
        val r = BigInteger(der.copyOfRange(i + 2, i + 2 + rLen))
        var j = i + 2 + rLen
        require(der[j].toInt() == 0x02) { "bad DER: expected INTEGER for s" }
        val sLen = der[j + 1].toInt()
        val s = BigInteger(der.copyOfRange(j + 2, j + 2 + sLen))
        val out = ByteArray(64)
        toFixed(r, out, 0)
        toFixed(s, out, 32)
        return out
    }

    private fun toFixed(v: BigInteger, out: ByteArray, offset: Int) {
        val b = v.toByteArray() // may have a leading 0x00 sign byte, or be short
        val src = if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else b
        System.arraycopy(src, 0, out, offset + (32 - src.size), src.size)
    }

    private companion object {
        private val JSON_MEDIA = "application/json".toMediaType()
        // APNs rejects a token older than 1h; refresh at 40 min.
        private const val JWT_REFRESH_MS = 40L * 60L * 1000L
        private val REASON = Regex("\"reason\"\\s*:\\s*\"(\\w+)\"")
    }
}

/** What APNs answered: its status, 0 when it was not reached, and its
 *  reason (e.g. "BadDeviceToken") when it refused. */
data class ApnsResult(val code: Int, val reason: String)

/** An alert's payload: the aps part iOS shows, and the fields the app
 *  reads on a tap (the same ones the Android data push carries). */
internal fun alertPayload(
    title: String,
    body: String,
    patp: String,
    whom: String,
    postId: String,
    parent: String? = null,
    nonce: String? = null,
    badge: Int? = null,
): String = buildString {
    append("{\"aps\":{\"alert\":{\"title\":\"").append(jsonEscape(title))
    append("\",\"body\":\"").append(jsonEscape(body))
    append("\"},\"sound\":\"default\",\"thread-id\":\"").append(jsonEscape(whom)).append('"')
    if (badge != null) append(",\"badge\":").append(badge)
    append("},\"event\":\"").append(if (nonce != null) "push-test" else "new-message")
    append("\",\"patp\":\"").append(jsonEscape(patp))
    append("\",\"whom\":\"").append(jsonEscape(whom))
    append("\",\"id\":\"").append(jsonEscape(postId)).append('"')
    if (parent != null) append(",\"parent\":\"").append(jsonEscape(parent)).append('"')
    if (nonce != null) append(",\"nonce\":\"").append(jsonEscape(nonce)).append('"')
    append('}')
}

/** A JSON string's contents: quote, backslash, and every control
 *  character escaped, so no text a peer chose can break the body. */
internal fun jsonEscape(s: String): String = buildString {
    for (c in s) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
}
