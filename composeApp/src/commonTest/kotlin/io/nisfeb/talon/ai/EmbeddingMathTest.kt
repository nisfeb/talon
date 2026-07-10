package io.nisfeb.talon.ai

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class EmbeddingMathTest {

    @Test
    fun roundTripsFloats() {
        val v = floatArrayOf(0f, 1f, -1f, 3.14159f, 1e-8f, 1e8f, Float.MIN_VALUE)
        val restored = unpackEmbedding(packEmbedding(v), v.size)
        assertContentEquals(v, restored)
    }

    @Test
    fun littleEndianByteLayoutMatchesLegacyByteBuffer() {
        // 1.0f == 0x3F800000; little-endian on-disk order is 00 00 80 3F.
        // This blob is stored in SQLite, so the byte order must not drift
        // from the old ByteBuffer(LITTLE_ENDIAN) representation.
        val bytes = packEmbedding(floatArrayOf(1.0f))
        assertContentEquals(
            byteArrayOf(0x00, 0x00, 0x80.toByte(), 0x3F),
            bytes,
        )
    }

    @Test
    fun packLengthIsFourBytesPerFloat() {
        assertEquals(12, packEmbedding(FloatArray(3)).size)
    }
}
