package io.nisfeb.talon.call

/**
 * Resample 16-bit little-endian mono PCM between sample rates.
 *
 * Needed because one recording legitimately holds clips captured at
 * different rates — the desktop self-mic is tapped off the capture
 * device at its native rate (often 44.1 kHz) while remote speakers
 * arrive decoded at 48 kHz. Mixing those by sample index plays the
 * slower one back too fast; [PcmMix] therefore requires a common rate
 * and this is how clips get there.
 *
 * ponytail: linear interpolation, no anti-aliasing filter. Speech at a
 * 44.1k <-> 48k ratio is the only case this sees, where the artefacts
 * are inaudible; a polyphase resampler is the upgrade if we ever
 * downsample by a large factor for something other than a mixdown.
 */
object PcmResample {

    /** [pcm] from [from] Hz to [to] Hz. Returns [pcm] itself when the
     *  rates already match or the input is too short to interpolate. */
    fun to(pcm: ByteArray, from: Int, to: Int): ByteArray {
        if (from <= 0 || to <= 0 || from == to) return pcm
        val inSamples = pcm.size / 2
        if (inSamples < 2) return pcm
        val outSamples = ((inSamples.toLong() * to) / from).toInt()
        if (outSamples <= 0) return ByteArray(0)
        val out = ByteArray(outSamples * 2)
        val step = (inSamples - 1).toDouble() / (outSamples - 1).coerceAtLeast(1)
        var i = 0
        while (i < outSamples) {
            val pos = i * step
            val a = pos.toInt()
            val b = if (a + 1 < inSamples) a + 1 else a
            val frac = pos - a
            val sa = sampleAt(pcm, a)
            val sb = sampleAt(pcm, b)
            val v = (sa + (sb - sa) * frac).toInt().coerceIn(-32768, 32767)
            val o = i * 2
            out[o] = (v and 0xFF).toByte()
            out[o + 1] = ((v shr 8) and 0xFF).toByte()
            i++
        }
        return out
    }

    private fun sampleAt(pcm: ByteArray, index: Int): Double {
        val b = index * 2
        val lo = pcm[b].toInt() and 0xFF
        val hi = pcm[b + 1].toInt() // keeps the sign
        return ((hi shl 8) or lo).toDouble()
    }
}
