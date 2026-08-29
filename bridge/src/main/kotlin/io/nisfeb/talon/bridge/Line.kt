package io.nisfeb.talon.bridge

import io.nisfeb.talon.util.Log
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/**
 * The sound card as a [PcmSource] / [PcmSink] pair.
 *
 * This is what makes the bridge two-way against something it cannot
 * speak to directly — an X Space, say. A person joins the Space in
 * their own client, and the bridge is that client's microphone and
 * speaker: the party line arrives at X as mic input, and the Space's
 * audio is captured back out. X never touches a real microphone or a
 * real speaker, which is what keeps the two directions from feeding
 * each other.
 *
 * Both ends must be *dedicated* devices, not the system's default
 * output and its monitor. Talon is very likely running on the same
 * machine, and a whole-system capture would push the party line's own
 * playback straight back into the Space. See the README for the two
 * null sinks this expects.
 *
 * On Linux, Java's sound layer sees only ALSA hardware — PipeWire and
 * PulseAudio sinks are invisible to it, so there is nothing useful to
 * select by name. Routing is done outside the process instead, with
 * PULSE_SINK / PULSE_SOURCE (verified: both steer a Java stream), or
 * `pactl move-sink-input` after the fact. Hence [DEFAULT_DEVICE].
 */
const val DEFAULT_DEVICE = "default"

private val LINE_FORMAT = AudioFormat(48_000f, 16, 1, true, false)

/** Mixers whose name contains [want], or the system default. */
private fun mixerFor(want: String, info: DataLine.Info): Mixer.Info? {
    if (want.isBlank() || want == DEFAULT_DEVICE) return null
    val match = AudioSystem.getMixerInfo().firstOrNull {
        it.name.contains(want, ignoreCase = true) &&
            AudioSystem.getMixer(it).isLineSupported(info)
    }
    if (match == null) {
        val had = AudioSystem.getMixerInfo().joinToString { it.name }
        error("no audio device matching \"$want\" — this machine has: $had")
    }
    return match
}

/**
 * Audio captured from a device and spoken into the party line.
 *
 * Never blocks: the pump calls this every 10ms and a stall here would
 * also stall the other direction. Whatever the line has ready is what
 * gets sent, and the caller pads the rest with silence — an
 * underfilled slab is a click, a stalled pump is a dropout.
 *
 * Measured on PipeWire: a capture line delivers nothing for its first
 * ~1.7 seconds after start, and reports isActive() == false the whole
 * time it is in fact running. So an empty read early on is normal, not
 * a fault, and isActive is not worth consulting. The first attempt at
 * verifying this path "failed" purely because the test window closed
 * before the line woke up.
 */
class LineInPcmSource(device: String = DEFAULT_DEVICE) : PcmSource {

    private val line: TargetDataLine

    init {
        val info = DataLine.Info(TargetDataLine::class.java, LINE_FORMAT)
        val mixer = mixerFor(device, info)
        line = if (mixer == null) {
            AudioSystem.getLine(info) as TargetDataLine
        } else {
            AudioSystem.getMixer(mixer).getLine(info) as TargetDataLine
        }
        // A quarter second of slack. Smaller starves on a busy box;
        // larger just adds latency to everything the Space says.
        line.open(LINE_FORMAT, LINE_FORMAT.frameSize * 12_000)
        line.start()
        Log.i(TAG, "capturing from ${mixer?.name ?: DEFAULT_DEVICE}")
    }

    override fun read(into: ByteArray, frames: Int, format: PcmFormat): Int {
        if (format.sampleRate != 48_000 || format.channels != 1) {
            if (warned.compareAndSet(false, true)) {
                Log.w(TAG, "the line is 48kHz mono; WebRTC asked for $format")
            }
            return 0
        }
        val want = minOf(frames * format.bytesPerFrame, into.size)
        // Only what is already buffered, rounded down to whole frames.
        val ready = (line.available() / format.bytesPerFrame) * format.bytesPerFrame
        if (ready <= 0) return 0
        val n = line.read(into, 0, minOf(want, ready))
        return n / format.bytesPerFrame
    }

    override fun close() {
        runCatching { line.stop() }
        runCatching { line.close() }
    }

    private val warned = java.util.concurrent.atomic.AtomicBoolean(false)

    private companion object {
        private const val TAG = "LineIn"
    }
}

/**
 * The party line, played out to a device.
 *
 * Drops a slab rather than blocking when the line is full, for the
 * same reason [LineInPcmSource] never waits: the pump drives both
 * directions, and one backed-up device must not silence the other.
 */
class LineOutPcmSink(device: String = DEFAULT_DEVICE) : PcmSink {

    private val line: SourceDataLine
    private var dropped = 0L

    init {
        val info = DataLine.Info(SourceDataLine::class.java, LINE_FORMAT)
        val mixer = mixerFor(device, info)
        line = if (mixer == null) {
            AudioSystem.getLine(info) as SourceDataLine
        } else {
            AudioSystem.getMixer(mixer).getLine(info) as SourceDataLine
        }
        line.open(LINE_FORMAT, LINE_FORMAT.frameSize * 12_000)
        line.start()
        Log.i(TAG, "playing out to ${mixer?.name ?: DEFAULT_DEVICE}")
    }

    override fun write(pcm: ByteArray, frames: Int, format: PcmFormat) {
        if (format.sampleRate != 48_000 || format.channels != 1) return
        val n = minOf(frames * format.bytesPerFrame, pcm.size)
        if (line.available() < n) {
            // Reported once a second at most: a steady trickle here
            // means the far end is consuming slower than 48kHz, which
            // is a routing problem worth seeing, not a blip.
            if (dropped++ % 100 == 0L) {
                Log.w(TAG, "output is backed up; dropped $dropped slabs")
            }
            return
        }
        line.write(pcm, 0, n)
    }

    override fun close() {
        runCatching { line.drain() }
        runCatching { line.stop() }
        runCatching { line.close() }
    }

    private companion object {
        private const val TAG = "LineOut"
    }
}

/**
 * Fans one stream to several sinks.
 *
 * So that recording stays available while the bridge is doing
 * something else with the same audio — a Space relay that is also
 * writing the party line to disk is the case this exists for.
 */
class TeePcmSink(private vararg val sinks: PcmSink) : PcmSink {
    override fun write(pcm: ByteArray, frames: Int, format: PcmFormat) {
        for (s in sinks) runCatching { s.write(pcm, frames, format) }
    }

    override fun close() {
        for (s in sinks) runCatching { s.close() }
    }
}
