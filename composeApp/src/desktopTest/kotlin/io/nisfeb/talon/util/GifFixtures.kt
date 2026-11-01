package io.nisfeb.talon.util

/** [gif] with a comment block holding [text] after its header and colour table, where a camera app puts one. */
internal fun gifWithComment(gif: ByteArray, text: String): ByteArray {
    val flags = gif[10].toInt() and 0xff
    val at = 13 + if (flags and 0x80 != 0) 3 * (1 shl ((flags and 7) + 1)) else 0
    val words = text.encodeToByteArray()
    val comment = byteArrayOf(0x21, 0xFE.toByte(), words.size.toByte()) + words + byteArrayOf(0)
    return gif.copyOfRange(0, at) + comment + gif.copyOfRange(at, gif.size)
}
