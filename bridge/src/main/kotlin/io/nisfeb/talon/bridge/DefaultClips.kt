package io.nisfeb.talon.bridge

import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthesized starter clips so the soundboard has something to press
 * on first run. Written once into the soundboard folder; delete any
 * you don't like, they don't come back while other clips exist.
 */
object DefaultClips {
    private const val RATE = 48_000
    private val format = PcmFormat(RATE, 1)

    val names = listOf("airhorn", "applause", "ding", "drumroll", "laser", "rimshot", "sad-trombone")

    /** Writes the clips into [dir] unless it already holds clips. Returns what was written. */
    fun ensure(dir: File): List<File> {
        val existing = dir.listFiles()?.filter { it.extension.lowercase() in setOf("wav", "ogg", "flac") }.orEmpty()
        if (existing.isNotEmpty()) return emptyList()
        dir.mkdirs()
        return names.map { n -> File(dir, "$n.wav").also { write(it, synth(n)) } }
    }

    private fun write(file: File, samples: DoubleArray) {
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val s = (samples[i].coerceIn(-1.0, 1.0) * 32767).toInt()
            bytes[2 * i] = (s and 0xff).toByte()
            bytes[2 * i + 1] = (s shr 8).toByte()
        }
        val sink = WavPcmSink(file)
        sink.write(bytes, samples.size, format)
        sink.close()
    }

    internal fun synth(name: String): DoubleArray = when (name) {
        "ding" -> render(1.5, { 880.0 }) { ph, t ->
            (sin(ph) + 0.5 * sin(2 * ph) + 0.25 * sin(3 * ph)) * exp(-3 * t) * 0.6
        }
        "laser" -> render(0.5, { t -> 2400 * exp(-7 * t) + 120 }) { ph, t -> sin(ph) * (1 - t / 0.5) * 0.6 }
        "airhorn" -> render(1.4, { t -> 190.0 - 25 * t }) { ph, t ->
            val a = attackRelease(t, 1.4, 0.02, 0.15)
            (saw(ph) + 0.5 * saw(2 * ph) + 0.3 * saw(3 * ph)) * a * 0.45
        }
        "rimshot" -> render(0.3, { 180.0 }) { ph, t ->
            noise() * exp(-60 * t) * 0.6 + sin(ph) * exp(-30 * t) * 0.35
        }
        "applause" -> applause()
        "drumroll" -> drumroll()
        "sad-trombone" -> trombone()
        else -> error("no such clip: $name")
    }

    /** Phase-integrated oscillator: [freq] may glide, the wave stays continuous. */
    private fun render(seconds: Double, freq: (Double) -> Double, wave: (phase: Double, t: Double) -> Double): DoubleArray {
        val out = DoubleArray((seconds * RATE).toInt())
        var ph = 0.0
        for (i in out.indices) {
            val t = i.toDouble() / RATE
            out[i] = wave(ph, t)
            ph += 2 * PI * freq(t) / RATE
        }
        return out
    }

    private fun saw(ph: Double): Double {
        val x = ph / (2 * PI)
        return 2 * (x - floor(x + 0.5))
    }

    private val rnd = Random(7)
    private fun noise() = rnd.nextDouble() * 2 - 1

    private fun attackRelease(t: Double, total: Double, attack: Double, release: Double): Double = when {
        t < attack -> t / attack
        t > total - release -> ((total - t) / release).coerceAtLeast(0.0)
        else -> 1.0
    }

    private fun applause(): DoubleArray {
        val out = DoubleArray((3.0 * RATE).toInt())
        val clap = (0.03 * RATE).toInt()
        var i = 0
        while (i < out.size) {
            val t = i.toDouble() / RATE
            val density = sin(PI * t / 3.0)
            if (rnd.nextDouble() < 0.02 * density) {
                val gain = 0.3 + 0.7 * rnd.nextDouble()
                for (k in 0 until clap) {
                    val j = i + k
                    if (j >= out.size) break
                    out[j] += noise() * exp(-k.toDouble() / clap * 5) * gain * 0.5
                }
            }
            i += (0.001 * RATE).toInt()
        }
        for (j in out.indices) out[j] = out[j].coerceIn(-1.0, 1.0)
        return out
    }

    private fun drumroll(): DoubleArray {
        val out = DoubleArray((3.5 * RATE).toInt())
        var t = 0.0
        var gap = 0.09
        while (t < 2.5) {
            val start = (t * RATE).toInt()
            val hit = (0.05 * RATE).toInt()
            for (k in 0 until hit) {
                val j = start + k
                if (j >= out.size) break
                val tt = k.toDouble() / RATE
                out[j] += noise() * exp(-tt * 80) * 0.5 + sin(2 * PI * 150 * tt) * exp(-tt * 40) * 0.4
            }
            t += gap
            gap = (gap * 0.96).coerceAtLeast(0.025)
        }
        val crash = (2.5 * RATE).toInt()
        for (j in crash until out.size) {
            val tt = (j - crash).toDouble() / RATE
            out[j] += noise() * exp(-tt * 2.5) * 0.7
        }
        for (j in out.indices) out[j] = out[j].coerceIn(-1.0, 1.0)
        return out
    }

    private fun trombone(): DoubleArray {
        val notes = listOf(233.08 to 0.45, 220.0 to 0.45, 207.65 to 0.45, 196.0 to 1.4)
        val total = notes.sumOf { it.second }
        var noteStart = 0.0
        var idx = 0
        return render(total, { t ->
            while (idx < notes.size - 1 && t >= noteStart + notes[idx].second) {
                noteStart += notes[idx].second
                idx++
            }
            val (f, len) = notes[idx]
            val local = t - noteStart
            val slide = if (local < 0.08) f * 1.03 - (f * 0.03) * (local / 0.08) else f
            val vib = if (idx == notes.size - 1) 1 + 0.02 * sin(2 * PI * 5.5 * local) else 1.0
            val fade = if (idx == notes.size - 1) 1.0 else 1.0
            slide * vib * fade + 0.0 * len
        }) { ph, t ->
            val local = t - noteStart
            val len = notes[idx].second
            var v = 0.0
            for (k in 1..8) v += sin(k * ph) / k
            v * attackRelease(local, len, 0.03, if (idx == notes.size - 1) 0.8 else 0.05) * 0.35
        }
    }
}
