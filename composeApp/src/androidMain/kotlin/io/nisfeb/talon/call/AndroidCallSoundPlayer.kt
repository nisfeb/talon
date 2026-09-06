package io.nisfeb.talon.call

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import io.nisfeb.talon.util.Log
import kotlin.concurrent.thread

/**
 * Call tones on Android, through AudioTrack.
 *
 * The usage is chosen per [ToneStream], and getting it wrong makes a
 * tone silently inaudible. USAGE_VOICE_COMMUNICATION_SIGNALLING reads
 * like the obvious choice — it is literally "the beeps a call makes
 * about itself" — but it maps to the legacy STREAM_DTMF, which the
 * silent switch mutes. So a user with the ringer off and media volume
 * up heard nothing at all when placing a call, which is what this
 * replaces.
 *
 * Now: a tone that is part of a call rides USAGE_VOICE_COMMUNICATION
 * (STREAM_VOICE_CALL), audible with the ringer off exactly as a
 * phone's earpiece is; only an incoming ring rides
 * USAGE_NOTIFICATION_RINGTONE, where the silent switch is supposed to
 * reach it.
 *
 * Not the same thing as [io.nisfeb.talon.notify.Ringer], which handles
 * an *incoming* call while the app is backgrounded and belongs to the
 * notification. This is for tones while the app is in front.
 */
class AndroidCallSoundPlayer : CallSoundPlayer {

    @Volatile private var looping = false
    @Volatile private var loopThread: Thread? = null

    override fun play(pcm: ByteArray, stream: ToneStream) {
        thread(isDaemon = true, name = "talon-tone") { writeOnce(pcm, stream) { true } }
    }

    override fun loop(pcm: ByteArray, gapMs: Int, stream: ToneStream) {
        stopLoop()
        looping = true
        // Publish the fact that WE are ringing. The push path used to
        // infer it from window visibility, which is false for a merely
        // backgrounded app whose controller is still running — so both
        // ringers sounded at once, neither taking audio focus.
        if (stream == ToneStream.Ringer) {
            io.nisfeb.talon.Notifications.inAppRinging = true
            // Push-first ordering: if the system ringtone already
            // started, stop it now that we are ringing in-app.
            runCatching { io.nisfeb.talon.notify.Ringer.stop() }
        }
        // The loop condition is ownership, not just the flag: a thread
        // stuck in AudioTrack.write outlives its 300ms join, and a
        // shared flag would resurrect it when the next loop() sets
        // `looping` back — two rings at once, which the glare path
        // (Outgoing then Incoming in one step) reaches. start=false so
        // loopThread is assigned before the body's first check.
        val t = thread(isDaemon = true, name = "talon-ring", start = false) {
            val self = Thread.currentThread()
            while (looping && loopThread === self) {
                writeOnce(pcm, stream) { looping && loopThread === self }
                var slept = 0
                while (looping && loopThread === self && slept < gapMs) {
                    Thread.sleep(50)
                    slept += 50
                }
            }
        }
        loopThread = t
        t.start()
    }

    override fun stopLoop() {
        io.nisfeb.talon.Notifications.inAppRinging = false
        // Null the thread before the flag: an orphan checks ownership,
        // so it dies at its next check even if a new loop() has already
        // set `looping` true again.
        val t = loopThread
        loopThread = null
        looping = false
        t?.let { runCatching { it.join(300) } }
    }

    private fun writeOnce(
        pcm: ByteArray,
        stream: ToneStream,
        keepGoing: () -> Boolean,
    ) {
        var track: AudioTrack? = null
        runCatching {
            val min = AudioTrack.getMinBufferSize(
                CallSounds.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(pcm.size)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(
                            when (stream) {
                                // STREAM_VOICE_CALL: follows the call's
                                // own routing and volume, and the silent
                                // switch does not reach it.
                                ToneStream.Call ->
                                    AudioAttributes.USAGE_VOICE_COMMUNICATION
                                // STREAM_RING: silenced along with every
                                // other ringtone, which is the point.
                                ToneStream.Ringer ->
                                    AudioAttributes.USAGE_NOTIFICATION_RINGTONE
                            },
                        )
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(CallSounds.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(min)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track!!.play()
            var off = 0
            val chunk = 4096
            while (off < pcm.size && keepGoing()) {
                val n = minOf(chunk, pcm.size - off)
                val wrote = track!!.write(pcm, off, n)
                if (wrote <= 0) break
                off += wrote
            }
        }.onFailure {
            Log.i(TAG, "could not play a tone: ${it.message}")
        }
        runCatching { track?.stop() }
        runCatching { track?.release() }
    }

    private companion object {
        private const val TAG = "AndroidCallSound"
    }
}
