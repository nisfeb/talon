package io.nisfeb.talon.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EndOfSpeechTest {
    private val rate = 16_000

    /** 100 ms of a square wave at [amplitude], whose RMS is the amplitude. */
    private fun frame(amplitude: Int): ByteArray {
        val samples = rate / 10
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val s = if (i % 2 == 0) amplitude else -amplitude
            out[2 * i] = (s and 0xFF).toByte()
            out[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Feed [frames] until the gate says stop; the milliseconds it listened. */
    private fun listen(gate: EndOfSpeech, frames: Sequence<ByteArray>): Long {
        var ms = 0L
        for (f in frames) {
            ms += 100
            if (!gate.offer(f)) return ms
        }
        error("still listening after ${ms}ms")
    }

    private fun quiet() = frame(100)
    private fun voice() = frame(3_000)

    @Test
    fun `speech then a pause ends it, after the pause`() {
        val gate = EndOfSpeech(rate)
        val ms = listen(gate, sequence {
            repeat(3) { yield(quiet()) }
            repeat(20) { yield(voice()) }
            while (true) yield(quiet())
        })
        assertTrue(gate.heard)
        assertEquals(300 + 2_000 + 1_800L, ms)
    }

    @Test
    fun `a short breath between words does not end it`() {
        val gate = EndOfSpeech(rate)
        val ms = listen(gate, sequence {
            yield(quiet())
            repeat(10) { yield(voice()) }
            repeat(17) { yield(quiet()) } // 1.7 s: under the 1.8 s pause
            repeat(10) { yield(voice()) }
            while (true) yield(quiet())
        })
        assertEquals(100 + 1_000 + 1_700 + 1_000 + 1_800L, ms)
    }

    @Test
    fun `silence alone gives up and keeps nothing`() {
        val gate = EndOfSpeech(rate)
        val ms = listen(gate, generateSequence { quiet() })
        assertFalse(gate.heard)
        assertEquals(8_000L, ms)
    }

    @Test
    fun `talking that never stops is cut at the cap`() {
        val gate = EndOfSpeech(rate, maxMs = 30_000)
        val ms = listen(gate, sequence {
            yield(quiet())
            while (true) yield(voice())
        })
        assertTrue(gate.heard)
        assertEquals(30_000L, ms)
    }

    @Test
    fun `starting to talk at once is heard at the first pause`() {
        val gate = EndOfSpeech(rate)
        // No quiet frame first: the floor is the voice until a pause.
        repeat(5) { assertTrue(gate.offer(voice())) }
        assertTrue(gate.offer(quiet()))
        assertTrue(gate.offer(voice()))
        assertTrue(gate.heard)
    }

    @Test
    fun `steady noise is the room, not speech, even after the mic starts on zeros`() {
        val gate = EndOfSpeech(rate)
        val ms = listen(gate, sequence {
            repeat(2) { yield(frame(0)) }
            while (true) yield(frame(1_500))
        })
        assertFalse(gate.heard, "a car's steady noise was taken for speech")
        assertEquals(8_000L, ms)
    }

    @Test
    fun `a voice over steady noise is heard`() {
        val gate = EndOfSpeech(rate)
        repeat(3) { gate.offer(frame(1_500)) }
        gate.offer(frame(5_000))
        assertTrue(gate.heard)
    }

    @Test
    fun `a whisper under the minimum is not speech`() {
        val gate = EndOfSpeech(rate)
        gate.offer(frame(50))
        gate.offer(frame(400))
        assertFalse(gate.heard)
    }

    @Test
    fun `the level follows the voice and stays between 0 and 1`() {
        val gate = EndOfSpeech(rate)
        gate.offer(frame(0))
        assertEquals(0f, gate.level)
        gate.offer(frame(3_000))
        assertEquals(0.5f, gate.level)
        gate.offer(frame(30_000))
        assertEquals(1f, gate.level)
    }

    @Test
    fun `an empty read keeps listening and counts no time`() {
        val gate = EndOfSpeech(rate, giveUpMs = 100)
        assertTrue(gate.offer(ByteArray(0)))
        assertTrue(gate.offer(ByteArray(1)))
        assertFalse(gate.offer(quiet()))
    }

    @Test
    fun `only the bytes read count`() {
        val gate = EndOfSpeech(rate)
        // A loud tail past the bytes read is stale buffer, not this read.
        val buf = quiet() + voice()
        gate.offer(buf, bytes = quiet().size)
        assertFalse(gate.heard)
    }

    @Test
    fun `rms reads signed little-endian samples`() {
        val pcm = byteArrayOf(0x10, 0x27, 0xF0.toByte(), 0xD8.toByte()) // 10000, -10000
        assertEquals(10_000.0, EndOfSpeech.rms(pcm, 2))
    }
}
