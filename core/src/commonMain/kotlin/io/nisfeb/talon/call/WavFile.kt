package io.nisfeb.talon.call

/**
 * Wrap raw PCM in a WAV container at an arbitrary sample rate.
 *
 * [CallSounds.wav] is fixed at 44.1kHz for the built-in tones; a
 * recording arrives at whatever rate the media stack decoded (usually
 * 48kHz), so its header must carry that rate or playback is pitched
 * wrong. 16-bit little-endian PCM, mono by default.
 */
object WavFile {

    fun encode(pcm: ByteArray, sampleRate: Int, channels: Int = 1): ByteArray {
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val header = ByteArray(44)
        fun ascii(at: Int, s: String) = s.forEachIndexed { i, c -> header[at + i] = c.code.toByte() }
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
        le16(22, channels); le32(24, sampleRate); le32(28, byteRate)
        le16(32, blockAlign); le16(34, bitsPerSample)
        ascii(36, "data"); le32(40, pcm.size)
        return header + pcm
    }
}
