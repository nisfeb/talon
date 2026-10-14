package io.nisfeb.talon.util

/**
 * [bytes] without what a picture says about where, when and on what it
 * was taken. Uploads land public at their URL (2026-10-11).
 *
 * JPEG keeps the image and what decodes it: JFIF, an ICC colour profile,
 * Adobe's colour marker, the tables, frames and scans. Every other APPn
 * segment and every comment goes: EXIF with its GPS, XMP, IPTC, maker
 * notes, and the multi-picture index. The extra pictures that index
 * points at (thumbnails, HDR gain maps) follow the end marker and go
 * too. Only the orientation tag comes back, so a photo stays upright.
 *
 * PNG drops its text, EXIF and time chunks, and anything after IEND.
 *
 * Anything else (WebP, HEIC) or a file that does not parse is decoded and
 * re-encoded to a metadata-free JPEG by [reencodeStrippingMetadata], so an
 * attached raw HEIC or WebP photo cannot leak its GPS even though the
 * byte-level strip above does not understand those containers. Only a file
 * that cannot be decoded as an image at all goes as it came.
 */
fun stripImageMetadata(bytes: ByteArray): ByteArray = runCatching {
    when {
        bytes.size > 4 && bytes[0] == FF && bytes[1] == 0xD8.toByte() && bytes[2] == FF -> stripJpeg(bytes)
        bytes.size > 8 && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] } -> stripPng(bytes)
        else -> null
    }
}.getOrNull() ?: reencodeStrippingMetadata(bytes) ?: bytes

/**
 * Decode [bytes] and re-encode to a JPEG carrying no metadata, or null if it
 * cannot be decoded as an image. The fallback for formats the byte-level strip
 * does not parse (WebP, HEIC) and for a corrupt-but-renderable file: the
 * decoded pixels hold no EXIF/GPS and the platform JPEG encoders write none.
 * Lossy (a re-compression) — the accepted cost of guaranteeing no leak over
 * passing the original through untouched.
 */
internal expect fun reencodeStrippingMetadata(bytes: ByteArray): ByteArray?

private const val FF = 0xFF.toByte()
private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
private val PNG_DROPPED = setOf("tEXt", "zTXt", "iTXt", "eXIf", "tIME")

private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xff
private fun be16(b: ByteArray, i: Int) = u8(b, i) shl 8 or u8(b, i + 1)
private fun be32(b: ByteArray, i: Int) = be16(b, i) shl 16 or be16(b, i + 2)
private fun ByteArray.has(at: Int, ascii: String) =
    at + ascii.length <= size && ascii.indices.all { this[at + it] == ascii[it].code.toByte() }

private fun stripJpeg(b: ByteArray): ByteArray? {
    val out = ByteArray(b.size + 36)
    var n = 0
    fun put(from: Int, to: Int) { b.copyInto(out, n, from, to); n += to - from }
    put(0, 2)
    var turned = false
    var i = 2
    while (i < b.size) {
        if (b[i] != FF) return null
        var m = i + 1
        while (m < b.size && b[m] == FF) m++
        if (m >= b.size) return null
        val marker = u8(b, m)
        when {
            marker == 0xD9 -> { put(m - 1, m + 1); return out.copyOf(n) }
            marker in 0xD0..0xD7 || marker == 0x01 -> { put(m - 1, m + 1); i = m + 1 }
            else -> {
                val end = m + 1 + be16(b, m + 1)
                if (end <= m + 2 || end > b.size) return null
                val body = m + 3
                val kept = when (marker) {
                    0xE0 -> b.has(body, "JFIF\u0000")
                    0xE2 -> b.has(body, "ICC_PROFILE\u0000")
                    0xEE -> true
                    in 0xE1..0xEF, 0xFE -> false
                    else -> true
                }
                if (kept) put(m - 1, end)
                else if (marker == 0xE1 && !turned) orientation(b, body, end)?.let { o ->
                    turned = true
                    upright(o).copyInto(out, n); n += 36
                }
                i = end
                if (marker == 0xDA) {
                    // The scan runs to the next marker: an FF not stuffed
                    // with 00 and not a restart.
                    var j = i
                    while (j + 1 < b.size && !(b[j] == FF && u8(b, j + 1).let { it != 0 && it !in 0xD0..0xD7 })) j++
                    if (j + 1 >= b.size) { put(i, b.size); return out.copyOf(n) }
                    put(i, j)
                    i = j
                }
            }
        }
    }
    return out.copyOf(n)
}

/** The EXIF orientation in an APP1 body, when it turns the picture. */
private fun orientation(b: ByteArray, at: Int, end: Int): Int? {
    if (!b.has(at, "Exif\u0000\u0000")) return null
    val t = at + 6
    val le = when {
        b.has(t, "II") -> true
        b.has(t, "MM") -> false
        else -> return null
    }
    fun u16(i: Int) = if (le) u8(b, i) or (u8(b, i + 1) shl 8) else be16(b, i)
    fun u32(i: Int) = if (le) u16(i) or (u16(i + 2) shl 16) else be32(b, i)
    if (t + 8 > end) return null
    val ifd = t + u32(t + 4)
    if (ifd < t || ifd + 2 > end) return null
    for (k in 0 until u16(ifd)) {
        val e = ifd + 2 + 12 * k
        if (e + 12 > end) return null
        if (u16(e) == 0x0112) return u16(e + 8).takeIf { it in 2..8 }
    }
    return null
}

/** A 36-byte APP1 holding only [orientation]. */
private fun upright(orientation: Int): ByteArray = byteArrayOf(
    FF, 0xE1.toByte(), 0, 34,
    'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0,
    'M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8,
    0, 1,
    0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0,
    0, 0, 0, 0,
)

private fun stripPng(b: ByteArray): ByteArray? {
    val out = ByteArray(b.size)
    PNG_SIGNATURE.copyInto(out)
    var n = PNG_SIGNATURE.size
    var i = n
    while (i + 12 <= b.size) {
        val len = be32(b, i)
        if (len < 0 || i + 12 + len > b.size) return null
        val type = b.decodeToString(i + 4, i + 8)
        val end = i + 12 + len
        if (type !in PNG_DROPPED) { b.copyInto(out, n, i, end); n += end - i }
        i = end
        if (type == "IEND") return out.copyOf(n)
    }
    return null
}
