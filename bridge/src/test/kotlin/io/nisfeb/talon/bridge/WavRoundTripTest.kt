package io.nisfeb.talon.bridge

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The seam, without any of WebRTC.
 *
 * A tone written by [WavPcmSink] must come back out of
 * [WavPcmSource] as the same tone — including through the rate and
 * channel conversion, which is the part that would silently produce
 * noise rather than fail.
 */
class WavRoundTripTest {

    private fun tone(frames: Int, rate: Int, channels: Int, hz: Double = 440.0): ByteArray {
        val out = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val v = (sin(2 * PI * hz * i / rate) * 12000).toInt().toShort()
            repeat(channels) { out.putShort(v) }
        }
        return out.array()
    }

    private fun temp(name: String): File =
        File.createTempFile(name, ".wav").also { it.deleteOnExit() }

    @Test
    fun aToneSurvivesTheRoundTrip() {
        val file = temp("tone")
        val format = PcmFormat(48_000, 1)
        val written = tone(48_000, format.sampleRate, format.channels)

        val sink = WavPcmSink(file)
        // In 10ms slabs, the way the pump delivers them.
        val slab = 480 * format.bytesPerFrame
        for (off in written.indices step slab) {
            sink.write(written.copyOfRange(off, minOf(off + slab, written.size)), 480, format)
        }
        assertEquals(48_000L, sink.frames)
        sink.close()

        val source = WavPcmSource(file)
        assertEquals(48_000, source.frames, "one second at 48k")

        val read = ByteArray(slab)
        val n = source.read(read, 480, format)
        assertEquals(480, n)
        // Identity path: same rate, same channels, so it should be
        // byte-for-byte what went in.
        assertTrue(
            read.copyOf(slab).contentEquals(written.copyOf(slab)),
            "the first slab came back changed",
        )
    }

    @Test
    fun aFileIsResampledAndFannedToWhatWebRtcAsksFor() {
        // The realistic case: someone hands the bridge a 44.1k stereo
        // file and WebRTC wants 48k mono.
        val file = temp("stereo")
        val sink = WavPcmSink(file)
        val src = PcmFormat(44_100, 2)
        val seconds = 1
        sink.write(tone(44_100 * seconds, src.sampleRate, src.channels), 44_100 * seconds, src)
        sink.close()

        val want = PcmFormat(48_000, 1)
        val source = WavPcmSource(file)
        val buf = ByteArray(480 * want.bytesPerFrame)

        var slabs = 0
        var loudest = 0
        while (true) {
            val n = source.read(buf, 480, want)
            if (n == 0) break
            slabs++
            val s = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            for (i in 0 until n) loudest = maxOf(loudest, abs(s.get(i).toInt()))
        }

        // 1s at 48k in 10ms slabs, give or take the final partial one.
        assertTrue(slabs in 99..100, "expected ~100 slabs of 10ms, got $slabs")
        assertTrue(loudest > 10_000, "the tone came out silent (peak $loudest)")
    }

    @Test
    fun aLoopingSourceNeverRunsDry() {
        val file = temp("short")
        val format = PcmFormat(48_000, 1)
        val sink = WavPcmSink(file)
        // 5ms — shorter than a single slab, so every read wraps.
        sink.write(tone(240, format.sampleRate, format.channels), 240, format)
        sink.close()

        val source = WavPcmSource(file, loop = true)
        val buf = ByteArray(480 * format.bytesPerFrame)
        repeat(10) {
            assertEquals(480, source.read(buf, 480, format), "a looping source went short")
        }
    }

    @Test
    fun anEmptySourceIsSilentRatherThanBroken() {
        val file = temp("empty")
        WavPcmSink(file).close()
        // Nothing was ever written, so there is no header to parse.
        val e = runCatching { WavPcmSource(file) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException, "expected a clear rejection, got $e")
    }
}
