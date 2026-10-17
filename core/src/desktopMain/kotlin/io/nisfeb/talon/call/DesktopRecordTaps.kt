package io.nisfeb.talon.call

import io.nisfeb.talon.util.Log
import java.util.concurrent.atomic.AtomicInteger

/** The recording taps a party line's links and a 1:1 call share on desktop. */
internal object DesktopRecordTaps {
    /**
     * Our own mic, recorded by a standalone AudioRecorder, or null when
     * there is no device or it would not start.
     *
     * A LOCAL track's addSink never fires in webrtc-java (a solo
     * recording captured only silence), so the mic is tapped apart from
     * the peer connection, which also means it cannot affect the
     * outgoing call. The clip is therefore the raw capture device: no
     * AEC, NS or AGC, unlike the outgoing track. On laptop speakers it
     * carries the far end's echo. webrtc-java 0.14.0 exposes no processed
     * local tap, so there is nothing to switch to.
     *
     * The caller stops the recorder, and must stop it at once if its
     * link closed while this was opening the device.
     */
    fun startMic(cb: (ByteArray, Int) -> Unit, frames: AtomicInteger, tag: String): dev.onvoid.webrtc.media.audio.AudioRecorder? {
        // Follow the mic the call is on. Taking the first enumerated
        // device recorded the wrong input for anyone who had picked
        // a non-default one: silence, if that device isn't live.
        val wanted = DesktopWebRtcFactory.preferredRecordingDeviceId
        fun pick(all: List<dev.onvoid.webrtc.media.audio.AudioDevice>?) =
            all?.firstOrNull { wanted != null && it.descriptor == wanted }
                ?: all?.firstOrNull()
        val device = runCatching {
            pick(DesktopWebRtcFactory.audioDeviceModule().recordingDevices)
        }.getOrNull() ?: runCatching {
            // Built only to enumerate, so dispose it again: this
            // fallback runs whenever the shared module is missing,
            // and each leaked module is a live native object.
            val adm = dev.onvoid.webrtc.media.audio.AudioDeviceModule()
            try {
                pick(adm.recordingDevices)
            } finally {
                runCatching { adm.dispose() }
            }
        }.getOrNull()
        if (device == null) {
            Log.w(tag, "record tap: no recording device; self clip will be silent")
            return null
        }
        // Held outside the runCatching so a throw part-way through
        // start() can still be stopped: start() builds and initialises
        // the native module before it can fail, and stop() is the only
        // thing that disposes it.
        val rec = dev.onvoid.webrtc.media.audio.AudioRecorder()
        val started = runCatching {
            rec.setAudioDevice(device)
            rec.setAudioSink(object : dev.onvoid.webrtc.media.audio.AudioSink {
                // Arg order verified against webrtc-java 0.14.0:
                // (data, nSamples, bytesPerSample, channels, sampleRate,
                //  captureDelayMs, clockDrift).
                override fun onRecordedData(
                    data: ByteArray,
                    nSamples: Int,
                    bytesPerSample: Int,
                    channels: Int,
                    sampleRate: Int,
                    captureDelayMs: Int,
                    clockDrift: Int,
                ) {
                    // Do not trust `bytesPerSample`: webrtc-java reports
                    // it as bytes per FRAME here, so a stereo capture
                    // device (the Windows default) sends 4 and the old
                    // `!= 2` guard dropped every frame. data.size /
                    // nSamples is unambiguous.
                    val ch = if (channels in 1..2) channels else 1
                    val n = if (nSamples > 0) nSamples else data.size / (ch * 2)
                    if (n <= 0) return
                    // Still only 16-bit PCM: 2 bytes per channel.
                    if (data.size / n != ch * 2) return
                    if (frames.getAndIncrement() == 0) {
                        Log.i(tag, "record tap: mic first frame rate=$sampleRate ch=$ch bytes=${data.size}")
                    }
                    cb(toMonoLe(data, ch, n), sampleRate)
                }
            })
            rec.start()
        }.onFailure {
            runCatching { rec.stop() }
            Log.w(tag, "record tap: mic recorder failed; self clip will be silent", it)
        }.isSuccess
        return if (started) rec else null
    }

    /** A remote speaker's decoded track sink, which DOES fire, as 16-bit mono to [cb]. */
    fun remoteSink(cb: (ByteArray, Int) -> Unit, frames: AtomicInteger, tag: String): dev.onvoid.webrtc.media.audio.AudioTrackSink =
        object : dev.onvoid.webrtc.media.audio.AudioTrackSink {
            override fun onData(
                audioData: ByteArray,
                bitsPerSample: Int,
                sampleRate: Int,
                numberOfChannels: Int,
                numberOfFrames: Int,
            ) {
                if (bitsPerSample != 16) return
                if (frames.getAndIncrement() == 0) {
                    Log.i(tag, "record tap: remote first frame rate=$sampleRate ch=$numberOfChannels")
                }
                cb(toMonoLe(audioData, numberOfChannels, numberOfFrames), sampleRate)
            }
        }

    /** WebRTC PCM frame -> 16-bit little-endian mono bytes. Copies,
     *  because webrtc may reuse the delivered array across callbacks.
     *  Downmixes >1 channel by averaging. */
    fun toMonoLe(
        pcm: ByteArray,
        channels: Int,
        frames: Int,
    ): ByteArray {
        if (channels <= 1) return pcm.copyOf()
        val out = ByteArray(frames * 2)
        for (f in 0 until frames) {
            var acc = 0
            for (c in 0 until channels) {
                val b = (f * channels + c) * 2
                if (b + 1 < pcm.size) {
                    val lo = pcm[b].toInt() and 0xFF
                    val hi = pcm[b + 1].toInt()
                    acc += (hi shl 8) or lo
                }
            }
            val v = acc / channels
            out[f * 2] = (v and 0xFF).toByte()
            out[f * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}
