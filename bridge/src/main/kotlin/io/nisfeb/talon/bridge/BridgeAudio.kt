package io.nisfeb.talon.bridge

import dev.onvoid.webrtc.media.audio.AudioTrackSink
import dev.onvoid.webrtc.media.audio.CustomAudioSource
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
import io.nisfeb.talon.call.DesktopPeerLink
import io.nisfeb.talon.call.DesktopWebRtcFactory
import io.nisfeb.talon.call.PeerLinkFactory
import io.nisfeb.talon.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where the party line meets the [PcmSource] / [PcmSink] seam.
 *
 * No virtual audio device on any platform — no PulseAudio sink, no
 * BlackHole, no VB-Cable, and no driver-install consent wall. There
 * is also no acoustic path between what we say and what we hear, so
 * echo cancellation and gain control are simply absent rather than
 * being tuned against a loop that doesn't exist.
 *
 * The wiring is not the one the class names suggest, and it was
 * worth measuring rather than assuming: an AudioDeviceModule's
 * setAudioSink / setAudioSource stop firing the moment a
 * PeerConnectionFactory takes the module over. They drive the
 * standalone AudioRecorder / AudioPlayer helpers, not a peer
 * connection. What does work, verified against a local loopback:
 *
 *     CustomAudioSource.pushAudio  → what we say into the line
 *     AudioTrack.addSink → onData  ← what the line says
 *
 * HeadlessAudioDeviceModule still earns its place: it keeps WebRTC
 * from opening a sound card on a machine that may not have one.
 */
class BridgeAudio(
    private val source: PcmSource,
    private val sink: PcmSink,
) {
    /** WebRTC's own cadence, and what pushAudio expects per call. */
    private val rate = 48_000
    private val channels = 1
    private val framesPerSlab = rate / 100 // 10ms

    /**
     * Created in [start], not here: constructing an audio device
     * module is what loads webrtc-java's JNI library, and
     * CustomAudioSource does not — build it first and it dies with
     * an UnsatisfiedLinkError on a perfectly good install.
     */
    private var mic: CustomAudioSource? = null
    private val running = AtomicBoolean(false)
    private var pump: Thread? = null

    private val mixer = Mixer(framesPerSlab, channels)
    private val loggedRemote = AtomicBoolean(false)

    /**
     * A [PeerLinkFactory] that sends our source and records to our
     * sink, for handing to `PartyLine` in place of the desktop one.
     */
    val peerLinks = PeerLinkFactory { ice, sendAudio ->
        DesktopPeerLink(
            ice,
            sendAudio,
            micPcm = if (sendAudio) checkNotNull(mic) { "call start() first" } else null,
            remotePcm = remoteSink,
        )
    }

    private val remoteSink = AudioTrackSink { data, bitsPerSample, sampleRate, ch, frames ->
        if (loggedRemote.compareAndSet(false, true)) {
            Log.i(TAG, "line audio: ${sampleRate}Hz×$ch, $bitsPerSample-bit, $frames frames/slab")
        }
        if (bitsPerSample == 16) mixer.add(data, frames, PcmFormat(sampleRate, ch), rate)
    }

    /**
     * Install the headless audio device and start moving PCM.
     *
     * Must run before anything touches the WebRTC factory, which
     * captures the module it was built with.
     */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        val module = HeadlessAudioDeviceModule()
        DesktopWebRtcFactory.useAudioDeviceModule { module }
        mic = CustomAudioSource()
        pump = Thread({ run() }, "bridge-audio").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * The bridge's clock.
     *
     * Both directions are paced here rather than by a callback:
     * pushAudio is push-driven, and remote tracks each deliver on
     * their own thread, so this thread is the one place that decides
     * what a 10ms slab is. Sleeping to a deadline rather than by
     * interval, so a slow tick doesn't accumulate into drift.
     */
    private fun run() {
        val format = PcmFormat(rate, channels)
        val out = ByteArray(framesPerSlab * format.bytesPerFrame)
        var next = System.nanoTime()
        while (running.get()) {
            runCatching {
                val frames = source.read(out, framesPerSlab, format)
                if (frames < framesPerSlab) {
                    java.util.Arrays.fill(out, frames * format.bytesPerFrame, out.size, 0)
                }
                // Push a full slab even when the source had nothing:
                // a gap in the RTP stream reads as a dropout, and
                // silence is the honest thing to send.
                mic?.pushAudio(out, 16, rate, channels, framesPerSlab)
            }.onFailure { Log.w(TAG, "could not send a slab", it) }

            runCatching {
                mixer.drain()?.let { sink.write(it, framesPerSlab, format) }
            }.onFailure { Log.w(TAG, "could not record a slab", it) }

            next += 10_000_000L
            val sleep = next - System.nanoTime()
            if (sleep > 0) {
                Thread.sleep(sleep / 1_000_000L, (sleep % 1_000_000L).toInt())
            } else {
                // Fell behind — give up the lost time rather than
                // sprinting to catch up, which would burst the line.
                next = System.nanoTime()
            }
        }
    }

    fun close() {
        running.set(false)
        pump?.join(500)
        pump = null
        // Deliberately not disposed. A peer connection that was
        // closed a moment ago still has native threads winding down
        // around the track this sourced, and freeing it underneath
        // them is a use-after-free — the same reason DesktopPeerLink
        // never disposes the shared factory. One native object
        // outliving the process costs nothing.
        mic = null
        runCatching { source.close() }
        runCatching { sink.close() }
    }

    private companion object {
        private const val TAG = "BridgeAudio"
    }
}

/**
 * Sums every remote track into one slab.
 *
 * A party line is one down link per speaker, each delivering on its
 * own thread at its own phase, so the alternative to summing is
 * interleaving them into noise. Everyone who lands in the same 10ms
 * window is mixed into that window.
 *
 * Channel counts are folded; sample rates are not. A stereo speaker
 * is averaged down, but a source at another *rate* would be summed
 * sample-for-sample and come out at the wrong speed, so it is
 * dropped with a warning instead — silence that says why beats a
 * recording that plays at half speed and doesn't.
 *
 * ponytail: no per-source jitter buffer — Galène hands out 48kHz and
 * a recording tolerates ±10ms of alignment slack. If rates ever do
 * vary, put an AudioResampler (webrtc-java ships one) in front.
 */
internal class Mixer(private val frames: Int, private val channels: Int) {
    private val lock = Any()
    private var acc = IntArray(frames * channels)
    private var any = false

    /** True once we have complained about a rate we can't take. */
    private val warned = java.util.concurrent.atomic.AtomicBoolean(false)

    fun add(data: ByteArray, frames: Int, format: PcmFormat, rate: Int) {
        if (format.sampleRate != rate) {
            if (warned.compareAndSet(false, true)) {
                Log.w(
                    "BridgeAudio",
                    "dropping a stream at ${format.sampleRate}Hz; the mixer runs at ${rate}Hz",
                )
            }
            return
        }
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        synchronized(lock) {
            val usable = minOf(frames, acc.size / channels, b.remaining() / format.channels)
            for (f in 0 until usable) {
                for (c in 0 until channels) {
                    acc[f * channels + c] += if (format.channels == channels) {
                        b.get(f * format.channels + c).toInt()
                    } else {
                        // Fold: average every input channel into each
                        // of ours, which is a downmix for stereo→mono
                        // and a duplicate for mono→stereo.
                        var sum = 0
                        for (sc in 0 until format.channels) sum += b.get(f * format.channels + sc)
                        sum / format.channels
                    }
                }
            }
            any = true
        }
    }

    /** The slab so far, or null if nobody spoke. */
    fun drain(): ByteArray? = synchronized(lock) {
        if (!any) return null
        val out = ByteBuffer.allocate(acc.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in acc) out.putShort(v.coerceIn(-32768, 32767).toShort())
        acc = IntArray(frames * channels)
        any = false
        out.array()
    }
}
