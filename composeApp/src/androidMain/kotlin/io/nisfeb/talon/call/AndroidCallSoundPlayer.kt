package io.nisfeb.talon.call

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import io.nisfeb.talon.util.Log
import kotlin.concurrent.thread

/**
 * Call tones on Android, through AudioTrack.
 *
 * USAGE_VOICE_COMMUNICATION_SIGNALLING is the attribute for exactly
 * this — the beeps a call makes about itself. It follows the call
 * routing, so the ringback comes out of whatever the call is using
 * rather than fighting it, and it ducks properly against media.
 *
 * Not the same thing as [io.nisfeb.talon.notify.Ringer], which handles
 * an *incoming* call while the app is backgrounded and belongs to the
 * notification. This is for tones while the app is in front.
 */
class AndroidCallSoundPlayer : CallSoundPlayer {

    @Volatile private var looping = false
    @Volatile private var loopThread: Thread? = null

    override fun play(pcm: ByteArray) {
        thread(isDaemon = true, name = "talon-tone") { writeOnce(pcm) { true } }
    }

    override fun loop(pcm: ByteArray, gapMs: Int) {
        stopLoop()
        looping = true
        loopThread = thread(isDaemon = true, name = "talon-ring") {
            while (looping) {
                writeOnce(pcm) { looping }
                var slept = 0
                while (looping && slept < gapMs) {
                    Thread.sleep(50)
                    slept += 50
                }
            }
        }
    }

    override fun stopLoop() {
        looping = false
        loopThread?.let { runCatching { it.join(300) } }
        loopThread = null
    }

    private fun writeOnce(pcm: ByteArray, keepGoing: () -> Boolean) {
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
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
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
