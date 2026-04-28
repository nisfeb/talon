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
