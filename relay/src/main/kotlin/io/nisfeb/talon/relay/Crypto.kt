package io.nisfeb.talon.relay

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

/**
 * AES-GCM ship-cookie encryption.
 *
 * Trust model (relay v1): operator supplies a master secret via
 * the `RELAY_MASTER_SECRET` env var. Cookies are encrypted with a
 * key derived from it (PBKDF2 + per-row salt). A SQLite-file leak
 * alone exposes ciphertext only; recovering cookies requires the
 * env var too. A full relay-box compromise (filesystem + memory)
 * exposes cookies — same threat model as a Tlon-hosted ship.
 *
 * Why not a per-user device secret: that model would require the
 * device to stay online to authorize each push, which defeats the
 * point of the relay (push WHEN the device is asleep / killed).
 * A per-user-secret variant is on the design doc's "future
 * hardening" list as opt-in for users who can tolerate the
 * online-only constraint.
 */
object Crypto {

    private const val PBKDF2_ITERATIONS = 100_000
    private const val PBKDF2_KEY_LEN_BITS = 256
    private const val GCM_TAG_LEN_BITS = 128
    private const val GCM_NONCE_LEN_BYTES = 12
    private const val SALT_LEN_BYTES = 16

    data class Sealed(
        val ciphertextB64: String,
        val saltB64: String,
        val nonceB64: String,
    )

    fun seal(plaintext: String, masterSecret: String): Sealed {
        val salt = randomBytes(SALT_LEN_BYTES)
        val nonce = randomBytes(GCM_NONCE_LEN_BYTES)
        val key = deriveKey(masterSecret, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LEN_BITS, nonce))
        }
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Sealed(
            ciphertextB64 = Base64.getEncoder().encodeToString(ciphertext),
            saltB64 = Base64.getEncoder().encodeToString(salt),
            nonceB64 = Base64.getEncoder().encodeToString(nonce),
        )
    }

    /** Returns null on any failure (wrong master secret, corrupted
     *  ciphertext, …). Callers should treat null as "skip this row,
     *  log + move on" — propagating the failure would let one bad
     *  row break SSE for everyone on the same relay. */
    fun open(sealed: Sealed, masterSecret: String): String? {
        return runCatching {
            val salt = Base64.getDecoder().decode(sealed.saltB64)
            val nonce = Base64.getDecoder().decode(sealed.nonceB64)
            val ciphertext = Base64.getDecoder().decode(sealed.ciphertextB64)
            val key = deriveKey(masterSecret, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LEN_BITS, nonce))
            }
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun deriveKey(secret: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(
            secret.toCharArray(),
            salt,
            PBKDF2_ITERATIONS,
            PBKDF2_KEY_LEN_BITS,
        )
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    private fun randomBytes(n: Int): ByteArray =
        ByteArray(n).also { SecureRandom().nextBytes(it) }
}
