package io.nisfeb.talon.bridge

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The mixer is the one place a party line's several speakers become
 * one recording, and every way it can be wrong is silent: summing a
 * stereo stream as if it were mono halves the speed, and a stream at
 * another rate does the same. AudioPathTest only ever runs one mono
 * 48k source through it, so the branches live here.
 */
class MixerTest {

    private val rate = 48_000
    private val frames = 4

    private fun slab(vararg values: Int): ByteArray =
        ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            values.forEach { putShort(it.toShort()) }
        }.array()

    private fun shorts(bytes: ByteArray): List<Int> {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return (0 until b.remaining()).map { b.get(it).toInt() }
    }

    @Test
    fun twoSpeakersAreSummed() {
        val mixer = Mixer(frames, 1)
        mixer.add(slab(100, 200, 300, 400), frames, PcmFormat(rate, 1), rate)
        mixer.add(slab(1, 2, 3, 4), frames, PcmFormat(rate, 1), rate)
        assertEquals(listOf(101, 202, 303, 404), shorts(mixer.drain()!!))
    }

    @Test
    fun aStereoSpeakerIsFoldedRatherThanHalfSpeeded() {
        val mixer = Mixer(frames, 1)
        // Four stereo frames: L/R pairs that average to 100..400.
        mixer.add(
            slab(50, 150, 100, 300, 250, 350, 300, 500),
            frames,
            PcmFormat(rate, 2),
            rate,
        )
        // Wrong would be [50, 150, 100, 300] — the left half of the
        // interleaved buffer read as if it were four mono frames.
        assertEquals(listOf(100, 200, 300, 400), shorts(mixer.drain()!!))
    }

    @Test
    fun aStreamAtTheWrongRateIsDroppedNotResampled() {
        val mixer = Mixer(frames, 1)
        mixer.add(slab(100, 200, 300, 400), frames, PcmFormat(44_100, 1), rate)
        assertNull(mixer.drain(), "a 44.1k stream would have played back at the wrong speed")
    }

    @Test
    fun silenceDrainsToNothing() {
        assertNull(Mixer(frames, 1).drain())
    }

    @Test
    fun aLoudSumClipsInsteadOfWrappingAround() {
        val mixer = Mixer(frames, 1)
        repeat(4) { mixer.add(slab(30_000, -30_000, 0, 0), frames, PcmFormat(rate, 1), rate) }
        // 120000 must saturate, not wrap to a negative.
        assertEquals(listOf(32_767, -32_768, 0, 0), shorts(mixer.drain()!!))
    }

    @Test
    fun drainingClearsTheSlab() {
        val mixer = Mixer(frames, 1)
        mixer.add(slab(1, 1, 1, 1), frames, PcmFormat(rate, 1), rate)
        mixer.drain()
        assertNull(mixer.drain(), "the previous slab leaked into the next one")
    }
}
