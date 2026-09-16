package io.nisfeb.talon.ui

import okio.ByteString.Companion.toByteString

/**
 * A ship's key fingerprint, the way Urbit computes one.
 *
 * A comet's @p *is* this value, which is why the mnemonym scheme can
 * name a comet from its name alone. Every other ship has a name that
 * says nothing about its keys, so to give one a word name you have to
 * go and get the keys and fingerprint them yourself. That is what the
 * reference library's `+come` does, and this is the same arithmetic.
 *
 * Ported against a live dojo rather than from reading alone, because
 * every step here is a place to get byte order wrong and a wrong
 * answer looks exactly like a right one. AzimuthFingerprintTest
 * carries the vectors it was checked against.
 *
 * Atoms are little-endian byte strings, which is the whole trick: an
 * atom's bytes run least-significant first, and Urbit's sha-256
 * returns a digest read the same way round.
 */
internal object AzimuthFingerprint {

    /**
     * The 128-bit fingerprint of an Azimuth ship's keys, or null when
     * the ship has none set or uses a suite this cannot read.
     *
     * [auth] and [crypt] are the 32-byte keys as Azimuth reports them,
     * most significant byte first, which is how they arrive as hex.
     */
    fun of(auth: ByteArray, crypt: ByteArray, suite: Int): ByteArray? {
        // +pass-from-eth substitutes empty keys for anything it does
        // not recognise, and a pass of zero is not a ship's identity.
        if (suite != 1 || auth.size != 32 || crypt.size != 32) return null
        if (auth.all { it == 0.toByte() } && crypt.all { it == 0.toByte() }) return null
        return shaf(BFIG, pass(auth, crypt))
    }

    /**
     * `(cat 3 'b' (cat 8 auth crypt))` as little-endian bytes: the
     * suite byte lowest, then the authentication key in its own
     * 32-byte block, then the encryption key in the next.
     */
    internal fun pass(auth: ByteArray, crypt: ByteArray): ByteArray {
        val out = ByteArray(1 + 32 + 32)
        out[0] = 'b'.code.toByte()
        for (i in 0 until 32) out[1 + i] = auth[31 - i]
        for (i in 0 until 32) out[33 + i] = crypt[31 - i]
        return out
    }

    /** `(mix (end 7 haz) (rsh 7 haz))` over `(shas sal ruz)`: the two
     *  halves of the hash folded together into 128 bits. */
    internal fun shaf(sal: ByteArray, ruz: ByteArray): ByteArray {
        val haz = shas(sal, ruz)
        return ByteArray(16) { (haz[it].toInt() xor haz[it + 16].toInt()).toByte() }
    }

    /** `(shay (max 32 (met 3 sal)) (mix sal (shax ruz)))`. */
    internal fun shas(sal: ByteArray, ruz: ByteArray): ByteArray {
        val len = maxOf(32, sal.size)
        val mixed = ByteArray(len)
        val inner = shax(ruz)
        for (i in 0 until len) {
            val a = if (i < sal.size) sal[i].toInt() else 0
            val b = if (i < inner.size) inner[i].toInt() else 0
            mixed[i] = (a xor b).toByte()
        }
        return sha256(mixed)
    }

    /** `(shay (met 3 ruz) ruz)`: the hash of the atom's own bytes,
     *  with the trailing zero bytes an atom would not have carried. */
    internal fun shax(ruz: ByteArray): ByteArray = sha256(trimAtom(ruz))

    /** An atom has no leading zeroes, so its byte string has no
     *  trailing ones. Passing them in would hash a different value. */
    private fun trimAtom(b: ByteArray): ByteArray {
        var n = b.size
        while (n > 0 && b[n - 1] == 0.toByte()) n--
        return if (n == b.size) b else b.copyOf(n)
    }

    private fun sha256(b: ByteArray): ByteArray = b.toByteString().sha256().toByteArray()

    /** The cord %bfig, least significant byte first. */
    private val BFIG = byteArrayOf('b'.code.toByte(), 'f'.code.toByte(), 'i'.code.toByte(), 'g'.code.toByte())
}
