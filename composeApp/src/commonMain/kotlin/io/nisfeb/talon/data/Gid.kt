package io.nisfeb.talon.data

import kotlin.random.Random

private const val HEX = "0123456789abcdef"

/**
 * A globally-unique id for a synced assistant conversation/turn. Local
 * autoincrement PKs collide across devices (device A and B both mint
 * id=1), so sync keys on this instead — 128 random bits, hex-encoded.
 * Matches the `lower(hex(randomblob(16)))` form the Room migration uses
 * to backfill rows that predate sync, so the two are interchangeable.
 */
fun newGid(): String {
    val bytes = Random.nextBytes(16)
    val sb = StringBuilder(32)
    for (b in bytes) {
        val i = b.toInt() and 0xff
        sb.append(HEX[i ushr 4]).append(HEX[i and 0x0f])
    }
    return sb.toString()
}
