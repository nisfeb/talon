package io.nisfeb.talon.ui

import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableStateFlow
import okio.ByteString.Companion.toByteString

/**
 * Mnemonym ship names — the friendlier fallback for ships without a
 * nickname, per the gwbtc/mnemonyms scheme (BIP-39-like): the ship's
 * value + a SHA-256 checksum (width/32 bits) split into 11-bit indices
 * into a 2048-word list, leading zero-index words dropped, words joined
 * with dots. `~sampel-palnet` → `.accept.engulf.relents`.
 *
 * What we encode is the value the @p syllables spell directly — for
 * planets and moons that is the ob-scrambled ("tweaked") form, which is
 * exactly what the scheme's `.` prefix denotes; encoding the raw point
 * would leak sponsor adjacency in the words. So no Feistel cipher here:
 * parse syllables, hash, done.
 *
 * Only planets (32-bit), moons (64) and comets (128) qualify — the
 * scheme needs a multiple of 32 bits, and galaxies/stars have short
 * memorable names already. Everything else returns null and the caller
 * shows the plain @p.
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
            encode(bytes, tweaked = true)
        }
    }.takeIf { it.length > 1 }

    /** Display form: planets keep all three words; moon/comet nyms are
     *  abridged to `.first...last` (the scheme's own abridge shape),
     *  like the truncated comet @p users already know. */
    fun display(ship: String): String? {
        val nym = forShip(ship) ?: return null
        val words = nym.trimStart('.').split('.')
        return if (words.size <= 3) nym else ".${words.first()}...${words.last()}"
    }

    private val nymLock = SynchronizedObject()
    private val nymCache = HashMap<String, String>()

    /** Big-endian bytes of the value the @p syllables encode, or null
     *  for galaxies/stars/malformed input. */
    internal fun patpBytes(ship: String): ByteArray? {
        if (!ship.startsWith("~")) return null
        val s = ship.drop(1).replace("-", "")
        // 4 syllables = planet, 8 = moon, 16 = comet. Galaxies (1) and
        // stars (2) stay @p; anything else isn't a ship name.
        if (s.length !in setOf(12, 24, 48) || s.length % 6 != 0) return null
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
 * Runtime switch for the mnemonym fallback, read by [ContactMap] via
 * [contactMapFlow]'s default parameter. Default ON — the platform
 * UiSettings impl loads the persisted value over it at startup and
 * wires [persist]; %settings sync applies remote changes through
 * [set], which never pushes back (only the Settings screen pushes),
 * so there is no ping-pong.
 */
object MnemonymNames {
    val enabled = MutableStateFlow(true)

    /** Wired by the platform UiSettings at startup; keeps the choice
     *  across launches. */
    var persist: (Boolean) -> Unit = {}

    fun set(value: Boolean) {
        enabled.value = value
        // A failed disk write must not take down the caller — set() runs
        // on the %settings apply path too. The value still applied live.
        runCatching { persist(value) }
    }
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
 *   3. the mnemonym, when [MnemonymNames] is on
 *   4. the raw @p
 */
object ShipNames {
    /** Ignore nicknames and mnemonyms entirely; show @p everywhere. */
    val alwaysPatp = MutableStateFlow(false)

    /** Wired by the platform UiSettings at startup, like
     *  [MnemonymNames.persist]. */
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
