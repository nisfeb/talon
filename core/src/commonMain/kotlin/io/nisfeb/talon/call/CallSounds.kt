package io.nisfeb.talon.call

import kotlin.math.PI
import kotlin.math.sin

/**
 * The sounds a call makes, synthesised rather than shipped.
 *
 * Four short tones as raw PCM, generated here so every platform hears
 * the same thing and only playback differs per leaf. No audio files to
 * license, package, or keep in step across three targets — and the
 * leave tone is genuinely the join tone reversed, which is one line on
 * a sample array and impossible with two hand-made assets.
 *
 * 16-bit signed little-endian mono at 44.1kHz: the one format every
 * platform's simplest playback API takes without a converter.
 */
object CallSounds {

    const val SAMPLE_RATE = 44_100
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16

    /**
     * Outgoing ringback: what *you* hear while their phone rings.
     *
     * 440Hz over 480Hz is the tone North American networks use, and it
     * is worth borrowing rather than inventing — people already know
     * what it means without being taught.
     */
    fun ringback(): ByteArray = tone(
        listOf(Partial(440.0), Partial(480.0)),
        seconds = 1.6,
        gain = 0.22,
    )

    /**
     * Incoming ring, for platforms with no system ringer of their own.
     *
     * Deliberately not the ringback: hearing your own outgoing tone
     * when someone calls you is disorienting. Higher and doubled, so it
     * reads as "attend to this" rather than "please wait".
     */
    fun incoming(): ByteArray =
        tone(listOf(Partial(660.0), Partial(880.0)), seconds = 0.45, gain = 0.25) +
            silence(0.18) +
            tone(listOf(Partial(660.0), Partial(880.0)), seconds = 0.45, gain = 0.25)

    /** Someone arrived: two notes, rising. */
    fun joined(): ByteArray =
        tone(listOf(Partial(587.33)), seconds = 0.11, gain = 0.20) +
            tone(listOf(Partial(880.0)), seconds = 0.16, gain = 0.20)

    /**
     * Someone left: [joined] played backwards.
     *
     * Reversing the samples, not just swapping the notes — the
     * envelope inverts too, so it fades in and stops dead where the
     * join tone starts sharp and rings out. That asymmetry is most of
     * why the two are distinguishable without being taught which is
     * which.
     */
    fun left(): ByteArray = reverseSamples(joined())

    /**
     * The same PCM with [gapMs] of silence after it.
     *
     * Lets a platform whose looping is native — iOS's AVAudioPlayer
     * repeats a buffer with no gap at all — get the same cadence as the
     * hand-rolled loops by baking the gap into the buffer, rather than
     * running a thread just to wait.
     */
    fun withGap(pcm: ByteArray, gapMs: Int): ByteArray =
        pcm + silence(gapMs / 1000.0)

    /**
     * Wrap PCM in a WAV container.
     *
     * For platforms whose simple playback API wants a file format
     * rather than raw samples. 44 bytes of well-known header, kept here
     * beside the synthesis so the two can never disagree about the
     * sample rate or width.
     */
    fun wav(pcm: ByteArray): ByteArray {
        val header = ByteArray(44)
        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8
        fun ascii(at: Int, text: String) {
            for (i in text.indices) header[at + i] = text[i].code.toByte()
        }
        fun le32(at: Int, v: Int) {
            header[at] = (v and 0xFF).toByte()
            header[at + 1] = ((v shr 8) and 0xFF).toByte()
            header[at + 2] = ((v shr 16) and 0xFF).toByte()
            header[at + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun le16(at: Int, v: Int) {
            header[at] = (v and 0xFF).toByte()
            header[at + 1] = ((v shr 8) and 0xFF).toByte()
        }
        ascii(0, "RIFF"); le32(4, 36 + pcm.size); ascii(8, "WAVE")
        ascii(12, "fmt "); le32(16, 16); le16(20, 1)
        le16(22, CHANNELS); le32(24, SAMPLE_RATE); le32(28, byteRate)
        le16(32, blockAlign); le16(34, BITS_PER_SAMPLE)
        ascii(36, "data"); le32(40, pcm.size)
        return header + pcm
    }

    // ---- synthesis -------------------------------------------------

    private data class Partial(val hz: Double)

    /**
     * A tone with a short attack and a longer decay.
     *
     * The envelope is the whole difference between a note and a click:
     * a raw sine switched on and off pops at both ends, because the
     * waveform jumps from silence to full amplitude in one sample.
     */
    private fun tone(
        partials: List<Partial>,
        seconds: Double,
        gain: Double,
    ): ByteArray {
        val n = (SAMPLE_RATE * seconds).toInt()
        val out = ByteArray(n * 2)
        val attack = (SAMPLE_RATE * 0.008).toInt().coerceAtLeast(1)
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            var v = 0.0
            for (p in partials) v += sin(2.0 * PI * p.hz * t)
            v /= partials.size
            val env = when {
                i < attack -> i.toDouble() / attack
                else -> {
                    val left = (n - i).toDouble() / (n - attack)
                    left * left
                }
            }
            writeSample(out, i, v * env * gain)
        }
        return out
    }

    private fun silence(seconds: Double): ByteArray =
        ByteArray((SAMPLE_RATE * seconds).toInt() * 2)

    private fun writeSample(out: ByteArray, index: Int, value: Double) {
        val clamped = value.coerceIn(-1.0, 1.0)
        val s = (clamped * Short.MAX_VALUE).toInt()
        out[index * 2] = (s and 0xFF).toByte()
        out[index * 2 + 1] = ((s shr 8) and 0xFF).toByte()
    }

    /** Reverse whole samples, not bytes — reversing bytes would swap
     *  each sample's halves and produce noise. */
    private fun reverseSamples(pcm: ByteArray): ByteArray {
        val n = pcm.size / 2
        val out = ByteArray(pcm.size)
        for (i in 0 until n) {
            val src = (n - 1 - i) * 2
            out[i * 2] = pcm[src]
            out[i * 2 + 1] = pcm[src + 1]
        }
        return out
    }
}

/**
 * Plays [CallSounds]. One impl per platform; [Noop] where there is no
 * playback path yet, so callers never have to check.
 */
interface CallSoundPlayer {

    /** Play once, over anything already playing of the same kind. */
    fun play(pcm: ByteArray)

    /** Loop [pcm], separated by [gapMs] of silence, until [stopLoop]. */
    fun loop(pcm: ByteArray, gapMs: Int)

    fun stopLoop()

    companion object {
        val Noop: CallSoundPlayer = object : CallSoundPlayer {
            override fun play(pcm: ByteArray) = Unit
            override fun loop(pcm: ByteArray, gapMs: Int) = Unit
            override fun stopLoop() = Unit
        }
    }
}
