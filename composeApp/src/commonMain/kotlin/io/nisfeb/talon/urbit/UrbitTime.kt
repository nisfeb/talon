package io.nisfeb.talon.urbit

import java.math.BigInteger

/**
 * Conversions between JS-style unix millis and Urbit's @da atom, plus
 * post id formatting. Ported from yap's ui/src/util/urbit-time.ts — the
 * DA_UNIX_EPOCH constant was back-solved from a real Tlon seal.id +
 * essay.sent pair; any drift here puts sent messages at junk sort keys.
 */
object UrbitTime {

    private val DA_SECOND: BigInteger = BigInteger.ONE.shiftLeft(64)
    private val DA_UNIX_EPOCH: BigInteger =
        BigInteger("170141184475152167957503069145530368000")
    private val ONE_THOUSAND: BigInteger = BigInteger.valueOf(1000)

    fun unixMsToDa(ms: Long): BigInteger =
        DA_UNIX_EPOCH + (BigInteger.valueOf(ms) * DA_SECOND) / ONE_THOUSAND

    /**
     * Inverse of [unixMsToDa]. Returns the unix-ms timestamp encoded
     * by a @da atom, or null when [da] sits before the unix epoch
     * (typically meaning a malformed or test-fixture value rather
     * than a real timestamp).
     *
     * The forward conversion truncates: `(ms * 2^64) / 1000` drops
     * up to 999/1000 of a tick. To round-trip cleanly, add `2^63`
     * (half a tick, in DA_SECOND units) before the final divide so
     * the reverse rounds half-up instead of truncating again — that
     * recovers the original ms regardless of which 1/1000 the forward
     * was sitting in.
     */
    fun daToUnixMs(da: BigInteger): Long? {
        if (da < DA_UNIX_EPOCH) return null
        val ms = ((da - DA_UNIX_EPOCH) * ONE_THOUSAND + DA_SECOND.shiftRight(1)) / DA_SECOND
        return ms.toLong()
    }

    /** Renders a @da as Urbit's dotted-decimal (3-digit groups, right-to-left). */
    fun daToUd(da: BigInteger): String {
        val digits = da.toString()
        val out = StringBuilder()
        var i = digits.length
        while (i > 0) {
            val start = maxOf(0, i - 3)
            if (out.isNotEmpty()) out.insert(0, '.')
            out.insert(0, digits.substring(start, i))
            i = start
        }
        return out.toString()
    }

    /** Tlon post id: "~author/<dotted-@da>". `author` must include the leading ~. */
    fun formatPostId(author: String, da: BigInteger): String =
        "$author/${daToUd(da)}"
}
