package io.nisfeb.talon.relay

import java.math.BigInteger

/**
 * Minimal `@da` ↔ unix-ms conversion. Ported from the client-side
 * `io.nisfeb.talon.urbit.UrbitTime` (we can't share that file across
 * the relay / composeApp module boundary, so this is a hand-copy of
 * just the conversion we need for the freshness filter).
 *
 * Keep in lockstep with the client copy if either changes — the
 * DA_UNIX_EPOCH constant is back-solved from a real Tlon seal.id.
 */
internal object UrbitTime {
    private val DA_SECOND: BigInteger = BigInteger.ONE.shiftLeft(64)
    private val DA_UNIX_EPOCH: BigInteger =
        BigInteger("170141184475152167957503069145530368000")
    private val ONE_THOUSAND: BigInteger = BigInteger.valueOf(1000)

    fun daToUnixMs(da: BigInteger): Long? {
        if (da < DA_UNIX_EPOCH) return null
        val ms = ((da - DA_UNIX_EPOCH) * ONE_THOUSAND + DA_SECOND.shiftRight(1)) / DA_SECOND
        return ms.toLong()
    }

    /**
     * Extract the unix-ms time from a Tlon post id of the form
     * `~author/<dotted-decimal-@da>` (the shape every activity event
     * carries in `dm-post.key.id` / `chan-post.key.id` /
     * `club-post.key.id`). Returns null when the id is malformed or
     * its @da component sits before the unix epoch.
     */
    fun postIdToMs(postId: String): Long? {
        val slash = postId.lastIndexOf('/')
        val daStr = if (slash >= 0) postId.substring(slash + 1) else postId
        val cleaned = daStr.replace(".", "")
        if (cleaned.isEmpty() || cleaned.any { !it.isDigit() }) return null
        return runCatching { daToUnixMs(BigInteger(cleaned)) }.getOrNull()
    }
}
