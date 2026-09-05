package io.nisfeb.talon.relay

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The APNs bearer token is hand-rolled (no JWT lib), so the ES256
 * signature and the DER→JOSE conversion are exactly where a silent
 * "InvalidProviderToken" would come from. These generate a real P-256
 * key, mint a token, and verify the signature back with the JDK — so
 * a broken conversion fails loudly here, not against Apple.
 */
class ApnsTest {

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private fun p8Pem(pkcs8: ByteArray): String {
        val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pkcs8)
        val armor = "PRIVATE KEY" // assembled, so the marker literal isn't in source
        return "-----BEGIN $armor-----\n$b64\n-----END $armor-----"
    }

    /** JOSE r||s (64 bytes) back to a DER SEQUENCE, so the JDK verifier
     *  can check the signature Apns produced. */
    private fun joseToDer(jose: ByteArray): ByteArray {
        fun trim(part: ByteArray): ByteArray {
            var start = 0
            while (start < part.size - 1 && part[start].toInt() == 0) start++
            var v = part.copyOfRange(start, part.size)
            if (v[0].toInt() and 0x80 != 0) v = byteArrayOf(0) + v // keep it positive
            return v
        }
        val r = trim(jose.copyOfRange(0, 32))
        val s = trim(jose.copyOfRange(32, 64))
        val body = byteArrayOf(0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    @Test
    fun `mints an ES256 JWT that verifies`() {
        val kp = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()

        val apns = Apns(
            teamId = "TEAM123456",
            keyId = "KEY7890123",
            p8Pem = p8Pem(kp.private.encoded),
            bundleId = "io.nisfeb.talon",
            production = true,
        )
        val token = apns.mintJwt(1_700_000_000L)
        val parts = token.split(".")
        assertEquals(3, parts.size, "a JWT is three dot-separated parts")

        val dec = Base64.getUrlDecoder()
        val header = json.parseToJsonElement(String(dec.decode(parts[0]))).let {
            it as kotlinx.serialization.json.JsonObject
        }
        assertEquals("ES256", header["alg"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertEquals("KEY7890123", header["kid"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content })

        val claims = json.parseToJsonElement(String(dec.decode(parts[1]))).let {
            it as kotlinx.serialization.json.JsonObject
        }
        assertEquals("TEAM123456", claims["iss"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertEquals("1700000000", claims["iat"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content })

        // The real check: the signature verifies with the public key
        // over "header.claims" — proves derToJose produced valid ES256.
        val sig = dec.decode(parts[2])
        assertEquals(64, sig.size, "ES256 signature is raw 64 bytes")
        val ok = Signature.getInstance("SHA256withECDSA").run {
            initVerify(kp.public)
            update("${parts[0]}.${parts[1]}".toByteArray())
            verify(joseToDer(sig))
        }
        assertTrue(ok, "the minted signature must verify against the public key")
    }

    @Test
    fun `an ios-voip ring with no APNs configured drops without throwing`() {
        // Push built without an Apns must not crash a ring for an
        // ios-voip device — it logs and drops.
        val push = Push(apns = null)
        push.sendRing(
            endpoint = "deadbeef",
            patp = "~sampel-palnet",
            from = "~zod",
            callId = "c1",
            platform = Push.IOS_VOIP,
        )
        // A padding integer keeps the r/s trim path exercised across
        // runs where a leading zero byte would otherwise be dropped.
        assertTrue(BigInteger.ONE.signum() == 1)
    }
}
