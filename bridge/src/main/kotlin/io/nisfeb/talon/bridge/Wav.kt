package io.nisfeb.talon.bridge

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WAV on both ends of the seam.
 *
 * The first thing the bridge could do, and a keeper rather than a
 * stepping stone: [WavPcmSink] is the recording feature. Playing a
 * file into a line and writing a line to disk exercises the ship
 * login, the ticket, the Galène join, the publish path and the
 * receive path end to end, with nothing to reverse-engineer — and
 * afterwards the same sink records a meeting.
 *
 * Only the one format WebRTC actually deals in is handled:
 * uncompressed 16-bit little-endian PCM. Anything else is rejected
 * loudly at open rather than played as noise.
 */
private const val HEADER_BYTES = 44

/**
 * Writes everything the line says to a .wav.
 *
 * The header can't be written until the first callback states the
 * format, and its two size fields aren't known until the end, so the
 * file is opened for random access and patched on close. A recording
 * of a process that was killed is still playable in most tools —
 * they read past a zero size — but [close] is what makes it correct,
 * hence the shutdown hook in [Bridge].
 */
class WavPcmSink(private val file: File) : PcmSink {

    private var out: RandomAccessFile? = null
    private var format: PcmFormat? = null
    private var dataBytes = 0L

    @Synchronized
    override fun write(pcm: ByteArray, frames: Int, format: PcmFormat) {
        val f = out ?: open(format)
        if (this.format != format) {
            // WebRTC restated the format mid-recording. Rare, and
            // rewriting the file isn't worth it; keeping the samples
            // at the wrong rate is worse than saying so.
            System.err.println("bridge: audio format changed ${this.format} → $format; ignoring")
            return
        }
        val n = frames * format.bytesPerFrame
        f.write(pcm, 0, minOf(n, pcm.size))
        dataBytes += minOf(n, pcm.size)
    }

    private fun open(fmt: PcmFormat): RandomAccessFile {
        file.parentFile?.mkdirs()
        val f = RandomAccessFile(file, "rw")
        f.setLength(0)
        f.write(header(fmt, dataBytes = 0))
        out = f
        format = fmt
        return f
    }

    @Synchronized
    override fun close() {
        val f = out ?: return
        val fmt = format
        runCatching {
            if (fmt != null) {
                f.seek(0)
                f.write(header(fmt, dataBytes))
            }
            f.close()
        }
        out = null
    }

    /** Frames written so far — the recording's length. */
    val frames: Long get() = format?.let { dataBytes / it.bytesPerFrame } ?: 0L
}

/**
 * Plays a .wav into the line, resampled to whatever WebRTC asks for.
 *
 * Resampling is nearest-neighbour and channel mapping is duplicate-
 * or-average. Both are crude, and deliberately: this is a test tone
 * and a hold-music path, not a mastering chain. A real stream source
 * (Icecast, SIP) arrives already at 48k and skips all of it.
 */
class WavPcmSource(file: File, private val loop: Boolean = false) : PcmSource {

    private val source: PcmFormat
    private val samples: ShortArray
    private var pos = 0

    init {
        val bytes = file.readBytes()
        require(bytes.size > HEADER_BYTES) { "${file.name}: too short to be a WAV" }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") {
            "${file.name}: not a RIFF/WAVE file"
        }
        // Walk the chunk list rather than assuming a 44-byte header:
        // plenty of encoders insert LIST/fact chunks before `data`.
        var off = 12
        var fmtChannels = 0
        var fmtRate = 0
        var dataOff = -1
        var dataLen = 0
        while (off + 8 <= bytes.size) {
            val id = String(bytes, off, 4)
            val len = b.getInt(off + 4)
            when (id) {
                "fmt " -> {
                    val audioFormat = b.getShort(off + 8).toInt()
                    require(audioFormat == 1) {
                        "${file.name}: only uncompressed PCM is supported (format $audioFormat)"
                    }
                    fmtChannels = b.getShort(off + 10).toInt()
                    fmtRate = b.getInt(off + 12)
                    val bits = b.getShort(off + 22).toInt()
                    require(bits == 16) { "${file.name}: only 16-bit PCM is supported ($bits-bit)" }
                }
                "data" -> {
                    dataOff = off + 8
                    dataLen = minOf(len, bytes.size - dataOff)
                }
            }
            if (len < 0) break
            off += 8 + len + (len and 1) // chunks are word-aligned
        }
        require(dataOff >= 0 && fmtRate > 0 && fmtChannels > 0) {
            "${file.name}: no fmt/data chunk"
        }
        source = PcmFormat(fmtRate, fmtChannels)
        samples = ShortArray(dataLen / 2)
        ByteBuffer.wrap(bytes, dataOff, dataLen).order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer().get(samples)
    }

    /** Frames available, counted in the file's own rate. */
    val frames: Int get() = samples.size / source.channels

    @Synchronized
    override fun read(into: ByteArray, frames: Int, format: PcmFormat): Int {
        val total = this.frames
        if (total == 0) return 0
        val out = ByteBuffer.wrap(into).order(ByteOrder.LITTLE_ENDIAN)
        val ratio = source.sampleRate.toDouble() / format.sampleRate
        var written = 0
        while (written < frames) {
            val srcFrame = (pos * ratio).toInt()
            if (srcFrame >= total) {
                if (!loop) break
                pos = 0
                continue
            }
            val base = srcFrame * source.channels
            // Mono→stereo duplicates; stereo→mono averages; matching
            // counts copy straight across.
            when {
                source.channels == format.channels ->
                    for (c in 0 until format.channels) out.putShort(samples[base + c])
                source.channels == 1 ->
                    repeat(format.channels) { out.putShort(samples[base]) }
                format.channels == 1 -> {
                    var sum = 0
                    for (c in 0 until source.channels) sum += samples[base + c]
                    out.putShort((sum / source.channels).toShort())
                }
                else -> for (c in 0 until format.channels)
                    out.putShort(samples[base + (c % source.channels)])
            }
            pos++
            written++
        }
        return written
    }

    @Synchronized
    override fun close() { pos = 0 }
}

/** A 44-byte canonical PCM WAV header. */
private fun header(fmt: PcmFormat, dataBytes: Long): ByteArray {
    val byteRate = fmt.sampleRate * fmt.bytesPerFrame
    val data = dataBytes.coerceAtMost(Int.MAX_VALUE.toLong() - HEADER_BYTES).toInt()
    return ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray())
        putInt(36 + data)
        put("WAVE".toByteArray())
        put("fmt ".toByteArray())
        putInt(16)              // PCM fmt chunk size
        putShort(1)             // uncompressed
        putShort(fmt.channels.toShort())
        putInt(fmt.sampleRate)
        putInt(byteRate)
        putShort(fmt.bytesPerFrame.toShort())
        putShort(16)            // bits per sample
        put("data".toByteArray())
        putInt(data)
    }.array()
}
