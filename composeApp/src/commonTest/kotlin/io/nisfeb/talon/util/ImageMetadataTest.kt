package io.nisfeb.talon.util

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertSame

/**
 * What a picture says about where, when and on what it was taken stays
 * off the public upload (2026-10-11). Built byte by byte, so each kind of
 * metadata has a marker to look for and every kept byte is pinned.
 */
class ImageMetadataTest {
    private fun ascii(s: String) = s.encodeToByteArray()
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun seg(marker: Int, payload: ByteArray) =
        bytes(0xFF, marker, (payload.size + 2) shr 8, (payload.size + 2) and 0xff) + payload

    /** An EXIF body: orientation [o] (none when null) and a GPS pointer at a block with [gps] in it. */
    private fun exif(o: Int?, gps: String, bigEndian: Boolean = false): ByteArray {
        fun u16(v: Int) = if (bigEndian) bytes(v shr 8, v and 0xff) else bytes(v and 0xff, v shr 8)
        fun u32(v: Int) = if (bigEndian) u16(v shr 16) + u16(v and 0xffff) else u16(v and 0xffff) + u16(v shr 16)
        val tiff = ascii(if (bigEndian) "MM" else "II") + u16(42) + u32(8) +
            u16(2) +
            u16(if (o == null) 0x0110 else 0x0112) + u16(3) + u32(1) + u16(o ?: 0) + u16(0) +
            u16(0x8825) + u16(4) + u32(1) + u32(38) +
            u32(0) + ascii(gps)
        return ascii("Exif\u0000\u0000") + tiff
    }

    private val soi = bytes(0xFF, 0xD8)
    private val eoi = bytes(0xFF, 0xD9)
    private val jfif = seg(0xE0, ascii("JFIF\u0000") + bytes(1, 1, 0, 0, 1, 0, 1, 0, 0))
    private val icc = seg(0xE2, ascii("ICC_PROFILE\u0000") + ascii("colours"))
    private val adobe = seg(0xEE, ascii("Adobe") + bytes(0, 100, 0, 0, 0, 0, 1))
    private val dqt = seg(0xDB, bytes(0, 1, 2, 3))
    private val sof = seg(0xC2, bytes(8, 0, 16, 0, 16, 1, 1, 0x11, 0))
    private val dht = seg(0xC4, bytes(0, 9, 9))
    private val sos = seg(0xDA, bytes(1, 1, 0, 0, 63, 0))
    // Entropy data with a stuffed FF and a restart marker inside it.
    private val scan1 = bytes(0x11, 0x22, 0xFF, 0x00, 0x33, 0xFF, 0xD0, 0x44)
    private val scan2 = bytes(0x55, 0xFF, 0x00, 0x66)

    private val upright6 = bytes(
        0xFF, 0xE1, 0, 34, 'E'.code, 'x'.code, 'i'.code, 'f'.code, 0, 0,
        'M'.code, 'M'.code, 0, 42, 0, 0, 0, 8, 0, 1,
        0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0, 0, 0, 0, 0,
    )

    @Test
    fun aPhotoKeepsItsPictureAndItsTurnAndNothingElse() {
        val photo = soi + jfif +
            seg(0xE1, exif(6, "GPS 40.7N 74.0W")) +
            seg(0xE1, ascii("http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta>Jane's iPhone</x:xmpmeta>")) +
            icc + seg(0xE2, ascii("MPF\u0000") + ascii("index")) + seg(0xED, ascii("Photoshop 3.0\u0000by-line")) +
            seg(0xFE, ascii("taken at home")) + seg(0xEF, ascii("vendor")) + seg(0xE3, ascii("vendor")) + adobe +
            dqt + sof + dht + sos + scan1 +
            // Progressive: tables between scans stay with them.
            dht + sos + scan2 + eoi +
            // A second picture after the end, such as an HDR gain map with its own EXIF.
            soi + seg(0xE1, exif(1, "GPS again")) + eoi
        val want = soi + jfif + upright6 + icc + adobe + dqt + sof + dht + sos + scan1 + dht + sos + scan2 + eoi
        assertContentEquals(want, stripImageMetadata(photo))
    }

    @Test
    fun aBigEndianTurnComesBackAndAnUprightPhotoNeedsNone() {
        val turned = soi + seg(0xE1, exif(6, "GPS", bigEndian = true)) + dqt + sos + scan2 + eoi
        assertContentEquals(soi + upright6 + dqt + sos + scan2 + eoi, stripImageMetadata(turned))
        val upright = soi + seg(0xE1, exif(1, "GPS")) + dqt + sos + scan2 + eoi
        assertContentEquals(soi + dqt + sos + scan2 + eoi, stripImageMetadata(upright))
        // 8 is the last turn there is; 9 is none, and neither is no tag at all.
        val eight = upright6.copyOf().also { it[29] = 8 }
        assertContentEquals(soi + eight + dqt + sos + scan2 + eoi, stripImageMetadata(soi + seg(0xE1, exif(8, "GPS")) + dqt + sos + scan2 + eoi))
        for (o in listOf(9, null)) {
            assertContentEquals(soi + dqt + sos + scan2 + eoi, stripImageMetadata(soi + seg(0xE1, exif(o, "GPS")) + dqt + sos + scan2 + eoi), "$o")
        }
    }

    @Test
    fun whatDoesNotParseGoesAsItCame() {
        val cut = soi + jfif + bytes(0xFF, 0xE1, 0x40, 0x00) + ascii("Exif")
        assertSame(cut, stripImageMetadata(cut))
        val text = ascii("not a picture at all")
        assertSame(text, stripImageMetadata(text))
        val noEnd = soi + dqt + sos + scan2
        assertContentEquals(noEnd, stripImageMetadata(noEnd), "a scan cut short keeps what it has")
    }

    private fun chunk(type: String, data: ByteArray) =
        bytes(data.size shr 24, data.size shr 16 and 0xff, data.size shr 8 and 0xff, data.size and 0xff) + ascii(type) + data + bytes(1, 2, 3, 4)

    @Test
    fun aPngLosesItsTextExifAndTime() {
        val sig = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val ihdr = chunk("IHDR", bytes(0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0))
        val idat = chunk("IDAT", bytes(9, 9, 9))
        val iend = chunk("IEND", ByteArray(0))
        val png = sig + ihdr + chunk("tEXt", ascii("Author\u0000Jane")) + chunk("eXIf", exif(1, "GPS")) +
            chunk("iTXt", ascii("XML:com.adobe.xmp\u0000\u0000\u0000\u0000\u0000x")) + chunk("zTXt", ascii("c\u0000\u0000z")) +
            chunk("tIME", bytes(7, 234, 10, 11, 14, 30, 0)) + idat + iend + ascii("after the end")
        assertContentEquals(sig + ihdr + idat + iend, stripImageMetadata(png))
        // A real picture's data runs past 64 KiB in one chunk.
        val big = chunk("IDAT", ByteArray(70_000) { 7 })
        assertContentEquals(sig + ihdr + big + iend, stripImageMetadata(sig + ihdr + chunk("tEXt", ascii("Author\u0000Jane")) + big + iend))
    }
}
