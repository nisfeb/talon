package io.nisfeb.talon.login

/**
 * Wire format for QR-code login handoff.
 *
 * A handoff QR encodes a `talon://login?url=<urlencoded>&code=<urlencoded>`
 * URI. Scanning it on the login screen pre-fills both fields and lets the
 * user connect without typing — handy for assisted onboarding flows where
 * the helper already has the ship URL + +code and wants to get the new
 * user past the form.
 *
 * The format intentionally mirrors a standard URI so any third-party QR
 * scanner can also recognize and dispatch it (deep-link future use).
 */
object TalonLoginUri {

    const val SCHEME = "talon"
    const val HOST = "login"

    /** Encoded login credentials. */
    data class Payload(val url: String, val code: String)

    /**
     * Build a `talon://login?url=...&code=...` URI from a [Payload].
     * Both values are URL-encoded.
     */
    fun encode(payload: Payload): String = buildString {
        append(SCHEME)
        append("://")
        append(HOST)
        append("?url=")
        append(urlEncode(payload.url))
        append("&code=")
        append(urlEncode(payload.code))
    }

    /**
     * Parse a candidate URI string into a [Payload], or null when the
     * input isn't a well-formed talon login URI. Tolerant of arbitrary
     * additional query params so future versions can add fields without
     * breaking older scanners.
     */
    fun decode(input: String): Payload? {
        val trimmed = input.trim()
        val schemeSep = trimmed.indexOf("://")
        if (schemeSep < 0) return null
        val scheme = trimmed.substring(0, schemeSep).lowercase()
        if (scheme != SCHEME) return null
        val rest = trimmed.substring(schemeSep + 3)
        val querySep = rest.indexOf('?')
        if (querySep < 0) return null
        val host = rest.substring(0, querySep).lowercase()
        if (host != HOST) return null
        val params = parseQuery(rest.substring(querySep + 1))
        val url = params["url"]?.takeIf { it.isNotBlank() } ?: return null
        val code = params["code"]?.takeIf { it.isNotBlank() } ?: return null
        return Payload(url = url, code = code)
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isEmpty()) return emptyMap()
        return q.split('&').asSequence()
            .mapNotNull { pair ->
                val eq = pair.indexOf('=')
                if (eq <= 0) return@mapNotNull null
                val k = pair.substring(0, eq)
                val v = pair.substring(eq + 1)
                k to urlDecode(v)
            }
            .toMap()
    }

    private fun urlEncode(s: String): String = buildString {
        for (c in s) {
            when {
                c.isLetterOrDigit() || c == '-' || c == '_' || c == '.' || c == '~' -> append(c)
                else -> {
                    // UTF-8 byte sequence → %HH per byte.
                    for (b in c.toString().encodeToByteArray()) {
                        append('%')
                        append(byteHex(b))
                    }
                }
            }
        }
    }

    private fun urlDecode(s: String): String {
        if ('%' !in s && '+' !in s) return s
        val bytes = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            when (val c = s[i]) {
                '+' -> {
                    bytes.add(' '.code.toByte())
                    i++
                }
                '%' -> {
                    if (i + 2 >= s.length) return s
                    val hi = hexNibble(s[i + 1]) ?: return s
                    val lo = hexNibble(s[i + 2]) ?: return s
                    bytes.add(((hi shl 4) or lo).toByte())
                    i += 3
                }
                else -> {
                    bytes.add(c.code.toByte())
                    i++
                }
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    private fun byteHex(b: Byte): String {
        val v = b.toInt() and 0xFF
        val hex = "0123456789ABCDEF"
        return "${hex[v ushr 4]}${hex[v and 0xF]}"
    }

    private fun hexNibble(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> null
    }
}
