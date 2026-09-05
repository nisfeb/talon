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
    fun sendVoip(voipToken: String, payload: String, expirationSecs: Int = 60) {
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
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // 410 Gone = the token is dead; the device must
                    // re-register. Surface the reason for the log; we
                    // don't side-effect the DB from here.
                    val reason = resp.body.string().take(200)
                    log.warn("apns voip HTTP ${resp.code} → ${voipToken.take(12)}… $reason")
                }
            }
        } catch (e: Throwable) {
            log.warn("apns voip failed → ${voipToken.take(12)}…: ${e.message}")
        }
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
    }
}
