package io.nisfeb.talon.ai


/** Pack a FloatArray into a little-endian byte blob for SQLite. */
fun packEmbedding(vector: FloatArray): ByteArray {
    val out = ByteArray(vector.size * 4)
    for (i in vector.indices) {
        val bits = vector[i].toRawBits()
        val o = i * 4
        out[o] = (bits and 0xFF).toByte()
        out[o + 1] = ((bits ushr 8) and 0xFF).toByte()
        out[o + 2] = ((bits ushr 16) and 0xFF).toByte()
        out[o + 3] = ((bits ushr 24) and 0xFF).toByte()
    }
    return out
}

/** Inverse of [packEmbedding] — read a little-endian Float blob. */
fun unpackEmbedding(bytes: ByteArray, dim: Int): FloatArray {
    val out = FloatArray(dim)
    for (i in 0 until dim) {
        val o = i * 4
        val bits = (bytes[o].toInt() and 0xFF) or
            ((bytes[o + 1].toInt() and 0xFF) shl 8) or
            ((bytes[o + 2].toInt() and 0xFF) shl 16) or
            ((bytes[o + 3].toInt() and 0xFF) shl 24)
        out[i] = Float.fromBits(bits)
    }
    return out
}

/** Dot product. For L2-normalized inputs this equals cosine
 *  similarity — the embedder produces normalized vectors so callers
 *  can use this directly instead of the more expensive [cosine]
 *  helper that re-normalizes. */
internal fun dotNorm(a: FloatArray, b: FloatArray): Float {
    var dot = 0f
    for (i in a.indices) dot += a[i] * b[i]
    return dot
}
