package io.nisfeb.talon.bridge

/**
 * The bridge's one pluggable seam: something that produces audio and
 * something that consumes it.
 *
 * Everything else in this module is the party line, which already
 * exists in `:core`. A source is what the bridge says into the line;
 * a sink is what it hears. Implement the pair and you have bridged a
 * party line to whatever you like — a file, an Icecast stream, a SIP
 * trunk, another party line.
 *
 * Buffers are interleaved signed 16-bit little-endian PCM, which is
 * what WebRTC's audio device hands over and asks for. The rate and
 * channel count are not ours to choose — WebRTC states them at each
 * callback and can change them — so they are a parameter of every
 * call rather than a property of the implementation.
 */
data class PcmFormat(val sampleRate: Int, val channels: Int) {
    /** Bytes in one frame: one sample per channel, 2 bytes each. */
    val bytesPerFrame: Int get() = channels * 2

    override fun toString() = "${sampleRate}Hz×$channels"
}

/** Audio going into the line. */
interface PcmSource {
    /**
     * Fill [into] with at most [frames] frames in [format], returning
     * how many were written. Short reads are padded with silence by
     * the caller; returning 0 means "nothing to say right now", which
     * is a normal state, not an error.
     *
     * Called from a native WebRTC thread every 10ms. Do not block on
     * a network read here — buffer elsewhere and hand over what's
     * ready.
     */
    fun read(into: ByteArray, frames: Int, format: PcmFormat): Int

    fun close() {}

    companion object {
        /** A bridge that only listens. */
        val Silent = object : PcmSource {
            override fun read(into: ByteArray, frames: Int, format: PcmFormat) = 0
        }
    }
}

/** Audio coming out of the line. */
interface PcmSink {
    /**
     * Take [frames] frames of [format] from the head of [pcm].
     *
     * Called from a native WebRTC thread every 10ms, with the same
     * "don't block" caveat as [PcmSource.read].
     */
    fun write(pcm: ByteArray, frames: Int, format: PcmFormat)

    fun close() {}

    companion object {
        /** A bridge that only speaks. */
        val Discard = object : PcmSink {
            override fun write(pcm: ByteArray, frames: Int, format: PcmFormat) {}
        }
    }
}
