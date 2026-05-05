package io.nisfeb.talon.relay

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins the AES-GCM round-trip + the failure modes we deliberately
 * swallow. The relay's "compromise of the SQLite file alone yields
 * ciphertext only" guarantee depends on these holding.
 */
class CryptoTest {

    private val plaintext = "urbauth-~sampel-palnet=0v3.deadbeef"
    private val secret = "this-is-the-relay-master-secret-ok"

    @Test
    fun `seal then open round-trips the plaintext`() {
        val sealed = Crypto.seal(plaintext, secret)
        assertEquals(plaintext, Crypto.open(sealed, secret))
    }

    @Test
    fun `wrong secret returns null without throwing`() {
        // Caller treats null as "skip this row, log and move on" —
        // raising would let one bad row break SSE for every other
        // user on the same relay.
        val sealed = Crypto.seal(plaintext, secret)
        assertNull(Crypto.open(sealed, "wrong-secret"))
    }

    @Test
    fun `tampered ciphertext returns null`() {
        val sealed = Crypto.seal(plaintext, secret)
        // Flip a byte deep in the ciphertext (after the auth tag);
        // GCM's MAC fails the open. No exception escapes.
        val bytes = java.util.Base64.getDecoder().decode(sealed.ciphertextB64)
        bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
        val tampered = sealed.copy(
            ciphertextB64 = java.util.Base64.getEncoder().encodeToString(bytes),
        )
        assertNull(Crypto.open(tampered, secret))
    }

    @Test
    fun `different seal calls produce different nonces`() {
        // GCM nonce reuse with the same key is catastrophic — pin
        // that we randomize per-call. Two seals of the same plaintext
        // must produce different ciphertexts.
        val a = Crypto.seal(plaintext, secret)
        val b = Crypto.seal(plaintext, secret)
        assertNotEquals(a.nonceB64, b.nonceB64)
        assertNotEquals(a.ciphertextB64, b.ciphertextB64)
    }

    @Test
    fun `salt varies per call so derived keys differ`() {
        // PBKDF2 salt randomization defends against rainbow tables
        // even if someone leaks both the database and the master
        // secret derivation function (without the secret itself).
        val a = Crypto.seal(plaintext, secret)
        val b = Crypto.seal(plaintext, secret)
        assertNotEquals(a.saltB64, b.saltB64)
    }

    @Test
    fun `corrupted nonce returns null`() {
        val sealed = Crypto.seal(plaintext, secret)
        val corruptedNonce = "AAAAAAAAAAAAAAAAAAAAAA=="  // wrong length-ish
        assertNull(
            Crypto.open(sealed.copy(nonceB64 = corruptedNonce), secret),
        )
    }

    @Test
    fun `empty plaintext is valid`() {
        // Edge case: a ship that returns an empty cookie body
        // shouldn't crash the relay.
        val sealed = Crypto.seal("", secret)
        assertEquals("", Crypto.open(sealed, secret))
    }

    @Test
    fun `unicode plaintext round-trips byte-perfect`() {
        // Cookies are ASCII, but the seal/open contract is general
        // bytes — pin UTF-8 round-trip so a future caller using
        // [Crypto] for non-cookie material doesn't get surprised.
        val unicode = "ünicode-test-✓-🔒"
        val sealed = Crypto.seal(unicode, secret)
        assertEquals(unicode, Crypto.open(sealed, secret))
    }
}
