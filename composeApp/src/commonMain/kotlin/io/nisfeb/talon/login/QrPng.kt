package io.nisfeb.talon.login

import okio.Buffer

/**
 * A [QrCodeGenerator] matrix as a PNG file: black on white, a quiet zone
 * of [border] cells, each cell [scale] pixels square.
 *
 * Grayscale with stored (uncompressed) deflate blocks. A QR image is a
 * few hundred pixels a side, so compression would save little, and it
 * would need a deflate implementation the shared code does not have.
 */
fun qrPng(matrix: Array<BooleanArray>, scale: Int = 8, border: Int = 4): ByteArray {
    val px = (matrix.size + border * 2) * scale
    // One filter byte (none) per scanline, then a gray byte per pixel.
    val raw = ByteArray((px + 1) * px)
    var o = 0
    for (y in 0 until px) {
        raw[o++] = 0
        val cy = y / scale - border
        for (x in 0 until px) {
            val cx = x / scale - border
            val dark = cy in matrix.indices && cx in matrix[cy].indices && matrix[cy][cx]
            raw[o++] = if (dark) 0 else 0xFF.toByte()
        }
    }
    val zlib = Buffer().apply {
        writeByte(0x78)
        writeByte(0x01)
        var i = 0
        do {
            val n = minOf(65_535, raw.size - i)
            writeByte(if (i + n == raw.size) 1 else 0)
            writeShortLe(n)
            writeShortLe(n.inv() and 0xFFFF)
            write(raw, i, n)
            i += n
        } while (i < raw.size)
        writeInt(adler32(raw))
    }.readByteArray()
    val header = Buffer().writeInt(px).writeInt(px).writeByte(8).writeByte(0).writeByte(0).writeByte(0).writeByte(0)
    return Buffer().apply {
        write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        chunk("IHDR", header.readByteArray())
        chunk("IDAT", zlib)
        chunk("IEND", ByteArray(0))
    }.readByteArray()
}

private fun Buffer.chunk(type: String, data: ByteArray) {
    val typed = type.encodeToByteArray() + data
    writeInt(data.size)
    write(typed)
    writeInt(crc32(typed))
}

private fun adler32(bytes: ByteArray): Int {
    var a = 1L
    var b = 0L
    for (x in bytes) {
        a = (a + (x.toInt() and 0xFF)) % 65_521
        b = (b + a) % 65_521
    }
    return ((b shl 16) or a).toInt()
}

private val crcTable = IntArray(256) { n ->
    var c = n
    repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
    c
}

private fun crc32(bytes: ByteArray): Int {
    var c = -1
    for (x in bytes) c = crcTable[(c xor x.toInt()) and 0xFF] xor (c ushr 8)
    return c.inv()
}
