package io.nisfeb.talon.util

/**
 * Multiplatform percent-encoding matching JS `encodeURIComponent` /
 * `decodeURIComponent` (and java.net.URLEncoder for the unreserved set
 * below) — commonMain can't reach java.net.URLEncoder. Used by the
 * `/cal` and `/tz` chat tags, which yap encodes the same way, so the
 * unreserved set must match encodeURIComponent exactly.
 */

// encodeURIComponent leaves these unescaped: A-Za-z0-9 - _ . ! ~ * ' ( )
private const val UNRESERVED = "-_.!~*'()"
private const val HEX = "0123456789ABCDEF"

fun percentEncodeComponent(s: String): String {
    val out = StringBuilder(s.length)
    for (b in s.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        if (c.toChar().let { it.isLetterOrDigit() && it.code < 128 } || c.toChar() in UNRESERVED) {
            out.append(c.toChar())
        } else {
            out.append('%').append(HEX[c ushr 4]).append(HEX[c and 0xF])
        }
    }
    return out.toString()
}

/** Reverse of [percentEncodeComponent]. Returns null on malformed input. */
fun percentDecodeComponent(s: String): String? {
    val bytes = ArrayList<Byte>(s.length)
    var i = 0
    while (i < s.length) {
        when (val ch = s[i]) {
            '%' -> {
                if (i + 2 >= s.length) return null
                val hi = HEX.indexOf(s[i + 1].uppercaseChar())
                val lo = HEX.indexOf(s[i + 2].uppercaseChar())
                if (hi < 0 || lo < 0) return null
                bytes.add(((hi shl 4) or lo).toByte())
                i += 3
            }
            '+' -> { bytes.add(' '.code.toByte()); i++ }
            else -> {
                for (b in ch.toString().encodeToByteArray()) bytes.add(b)
                i++
            }
        }
    }
    return bytes.toByteArray().decodeToString()
}
