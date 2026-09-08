package io.nisfeb.talon.call

/**
 * Interleaves per-speaker mono 16-bit tracks into one multichannel
 * frame stream, one channel per speaker, padded with silence to the
 * longest track. Tracks must already share a sample rate and t=0,
 * which [RecordedCall] guarantees.
 */
object PcmTracks {
    fun interleave(tracks: List<ByteArray>): ByteArray {
        if (tracks.isEmpty()) return ByteArray(0)
        val frames = tracks.maxOf { it.size / 2 }
        val n = tracks.size
        val out = ByteArray(frames * n * 2)
        for ((c, t) in tracks.withIndex()) {
            var i = 0
            val samples = t.size / 2
            while (i < samples) {
                val o = (i * n + c) * 2
                out[o] = t[2 * i]
                out[o + 1] = t[2 * i + 1]
                i++
            }
        }
        return out
    }
}
