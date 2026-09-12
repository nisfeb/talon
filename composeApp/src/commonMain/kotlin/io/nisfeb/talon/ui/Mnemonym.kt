package io.nisfeb.talon.ui

import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableStateFlow
import okio.ByteString.Companion.toByteString

/**
 * Mnemonym ship names for comets, per gwbtc/mnemonyms.
 *
 * A comet's @p is not scrambled the way a planet's or a moon's is: the
 * syllables spell its 128-bit key fingerprint directly. That is the
 * whole reason the scheme can name one. The nym is the fingerprint
 * plus a four-bit SHA-256 checksum, cut into 11-bit indices into a
 * 2048-word list, leading zero-index words dropped, joined with dots
 * and prefixed `..` -- two dots for untweaked, the scheme's mark for a
 * value nobody had to unscramble.
 *
 * Only comets. Planets and moons have an ob-scrambled @p that spells
 * no key at all, so a nym built from it would look like the real thing
 * and mean nothing; naming one properly means fetching its public key
 * and fingerprinting that (`cometize` in the reference), which needs
 * the network and is not something a name lookup can do. Galaxies and
 * stars are already short. Everything but a comet returns null and the
 * caller shows the plain @p.
 */
object Mnemonym {

    /** Full nym for [ship], or null when the ship isn't a planet/moon/
     *  comet or doesn't parse. Verified against the reference
     *  implementation's test vectors. Memoized — displayName and the
     *  mention matcher run per row/keystroke, and the answer never
     *  changes. */
    fun forShip(ship: String): String? = synchronized(nymLock) {
        nymCache.getOrPut(ship) {
            val bytes = patpBytes(ship) ?: return@getOrPut ""
            encode(bytes, tweaked = false)
        }
    }.takeIf { it.isNotEmpty() }

    /** Display form: the scheme's own abridgement, `..first...last`,
     *  which is two words however long the nym is -- the same shape as
     *  the truncated comet @p people already read. Short nyms (a value
     *  with enough leading zeros to lose most of its words) are already
     *  that short and are left alone. */
    fun display(ship: String): String? = abridge(forShip(ship) ?: return null)

    /**
     * Display form for a fingerprint that was fetched rather than read
     * out of a name -- a planet's or a moon's, looked up from its
     * Azimuth keys.
     *
     * [fingerprint] is an atom's bytes, least significant first, which
     * is the order Urbit hands one over in. The encoder reads a value
     * the other way round, most significant first, like the syllables
     * of a @p. Getting this seam backwards produces a name that looks
     * entirely real, so it is spelled out rather than left to a cast.
     */
    fun displayFingerprint(fingerprint: ByteArray): String? =
        encodeFingerprint(fingerprint)?.let(::abridge)

    /** The unabridged nym for a fetched fingerprint. */
    fun encodeFingerprint(fingerprint: ByteArray): String? {
        if (fingerprint.size != 16) return null
        return encode(fingerprint.reversedArray(), tweaked = false)
    }

    /** The scheme's own abridgement: two words, however many there
     *  were. A nym already that short is left alone. */
    private fun abridge(nym: String): String {
        val words = nym.removePrefix("..").split('.')
        return if (words.size <= 2) nym else "..${words.first()}...${words.last()}"
    }

    private val nymLock = SynchronizedObject()
    private val nymCache = HashMap<String, String>()

    /** Big-endian bytes of the value the @p syllables encode, or null
     *  for galaxies/stars/malformed input. */
    internal fun patpBytes(ship: String): ByteArray? {
        if (!ship.startsWith("~")) return null
        val s = ship.drop(1).replace("-", "")
        // 16 syllables, and only 16: that is a comet, whose @p spells
        // its key fingerprint. Everything shorter is a scrambled or
        // already-short name the scheme has nothing to say about.
        if (s.length != 48) return null
        val bytes = ByteArray(s.length / 3)
        for (i in bytes.indices) {
            val syllable = s.substring(i * 3, i * 3 + 3)
            val idx = if (i % 2 == 0) prefixIndex[syllable] else suffixIndex[syllable]
            bytes[i] = (idx ?: return null).toByte()
        }
        return bytes
    }

    /** Core encoding. [tweaked] only selects the prefix (`.` vs `..`);
     *  internal so tests can pin the untweaked official vectors too. */
    internal fun encode(bytes: ByteArray, tweaked: Boolean): String {
        val width = bytes.size * 8
        val csLen = width / 32
        val sha = bytes.toByteString().sha256().toByteArray()
        val checksum = (sha[0].toInt() and 0xff) ushr (8 - csLen)
        // Positive big-endian integer from the point bytes.
        val value = bytes.fold(BigInteger.ZERO) { acc, b ->
            acc.shl(8).or(BigInteger.fromInt(b.toInt() and 0xff))
        }
        var combined = value.shl(csLen).or(BigInteger.fromLong(checksum.toLong()))
        val total = (width + csLen) / 11
        val indices = IntArray(total)
        for (k in total - 1 downTo 0) {
            indices[k] = combined.and(MASK_11).intValue(exactRequired = false)
            combined = combined.shr(11)
        }
        val words = indices.asList().dropWhile { it == 0 }.map { MNEMONYM_WORDS[it] }
        return (if (tweaked) "." else "..") + words.joinToString(".")
    }

    private val MASK_11 = BigInteger.fromInt(0x7FF)
    private val prefixIndex: Map<String, Int> =
        PATP_PREFIXES.withIndex().associate { (i, s) -> s to i }
    private val suffixIndex: Map<String, Int> =
        PATP_SUFFIXES.withIndex().associate { (i, s) -> s to i }
}

/**
 * Reader-side naming policy, in one place.
 *
 * Every surface that shows a ship — a row title, a mention inside a
 * message, a quoted post's author — asks this what the *reader* wants
 * to see, so one person's nickname for someone never leaks into
 * another person's view. Precedence:
 *
 *   1. [alwaysPatp] on  -> the raw @p, always
 *   2. a nickname the reader has for that ship
 *   3. the mnemonym, for a comet
 *   4. the raw @p
 */
object ShipNames {
    /** Ignore nicknames and mnemonyms entirely; show @p everywhere. */
    val alwaysPatp = MutableStateFlow(false)

    /** Wired by the platform UiSettings at startup. */
    var persist: (Boolean) -> Unit = {}

    fun setAlwaysPatp(value: Boolean) {
        alwaysPatp.value = value
        runCatching { persist(value) }
    }

    /**
     * Resolve a ship to the reader's preferred name. Set from the live
     * ContactMap, so it sees nicknames; defaults to the @p for code
     * that runs before any contact data exists.
     */
    @Volatile
    var resolve: (String) -> String = { it }
        private set

    /**
     * Bumped whenever [resolve] would start giving different answers.
     * Caches that store *rendered* output (StoryCache) key on this, so
     * a nickname edit or a settings flip re-renders instead of serving
     * a stale name.
     */
    val generation = MutableStateFlow(0)

    fun setResolver(version: Int, resolver: (String) -> String) {
        if (generation.value == version) return
        resolve = resolver
        generation.value = version
    }
}
