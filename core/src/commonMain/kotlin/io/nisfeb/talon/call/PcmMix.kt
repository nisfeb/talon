package io.nisfeb.talon.call

/**
 * Mix several speakers' PCM into one track for the "keep the full
 * recording" option.
 *
 * Input is 16-bit little-endian mono PCM, one buffer per speaker, all
 * at the same sample rate. Samples are summed and clipped. Buffers of
 * different lengths are handled by treating missing samples as silence,
 * so the mix is as long as the longest speaker.
 *
 * ponytail: alignment is by sample index from zero. The recorder is
 * responsible for pre-padding each speaker's buffer with leading
 * silence to the moment recording started, since it holds the timing;
 * this keeps the mix a pure array op. Wrap the result with
 * [CallSounds.wav] to get a playable file.
 */
object PcmMix {

    fun mix(tracks: List<ByteArray>): ByteArray {
        if (tracks.isEmpty()) return ByteArray(0)
        if (tracks.size == 1) return tracks[0]
        // Longest track in whole samples (2 bytes each).
        val maxSamples = tracks.maxOf { it.size / 2 }
        val out = ByteArray(maxSamples * 2)
        var i = 0
        while (i < maxSamples) {
            var acc = 0
            for (t in tracks) {
                val b = i * 2
                if (b + 1 < t.size) {
                    // signed 16-bit LE
                    val lo = t[b].toInt() and 0xFF
                    val hi = t[b + 1].toInt() // keep sign
                    acc += (hi shl 8) or lo
                }
            }
            val clamped = if (acc > 32767) 32767 else if (acc < -32768) -32768 else acc
            val o = i * 2
            out[o] = (clamped and 0xFF).toByte()
            out[o + 1] = ((clamped shr 8) and 0xFF).toByte()
            i++
        }
        return out
    }
}
