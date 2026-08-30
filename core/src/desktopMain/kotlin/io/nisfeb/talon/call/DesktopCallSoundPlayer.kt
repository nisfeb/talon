package io.nisfeb.talon.call

import io.nisfeb.talon.util.Log
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread

/**
 * Call tones on desktop, through javax.sound.
 *
 * A [SourceDataLine] rather than a Clip: a Clip has to be closed and
 * reopened to replay, and the looping ring needs to stop the instant a
 * call is answered rather than at the end of the current pass.
 *
 * [ToneStream] is ignored: desktop has no silent switch and no
 * per-usage volumes, so every tone goes to the default output.
 *
 * Deliberately separate from the WebRTC audio device — these are UI
 * sounds and belong on the system's default output, not on whatever
 * headset the call is routed to. Hearing the ring in one ear and the
 * caller in the other is worse than either.
 */
class DesktopCallSoundPlayer : CallSoundPlayer {

    private val format = AudioFormat(
        CallSounds.SAMPLE_RATE.toFloat(),
        CallSounds.BITS_PER_SAMPLE,
        CallSounds.CHANNELS,
        true,
        false,
    )

    @Volatile private var looping = false
    @Volatile private var loopThread: Thread? = null

    override fun play(pcm: ByteArray, stream: ToneStream) {
        thread(isDaemon = true, name = "talon-tone") { writeOnce(pcm) }
    }

    override fun loop(pcm: ByteArray, gapMs: Int, stream: ToneStream) {
        stopLoop()
        looping = true
        loopThread = thread(isDaemon = true, name = "talon-ring") {
            while (looping) {
                writeOnce(pcm)
                // Sleep in slices so stopping is felt immediately
                // rather than after a four-second gap.
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

    private fun writeOnce(pcm: ByteArray) {
        var line: SourceDataLine? = null
        runCatching {
            line = AudioSystem.getSourceDataLine(format).apply {
                open(format)
                start()
            }
            // Write in chunks and re-check `looping`, so a ring that is
            // answered mid-tone stops now rather than finishing.
            var off = 0
            val chunk = 4096
            while (off < pcm.size) {
                if (loopThread === Thread.currentThread() && !looping) break
                val n = minOf(chunk, pcm.size - off)
                line!!.write(pcm, off, n)
                off += n
            }
            line!!.drain()
        }.onFailure {
            // A machine with no audio device is not an error worth
            // interrupting a call for.
            Log.i(TAG, "could not play a tone: ${it.message}")
        }
        runCatching { line?.stop() }
        runCatching { line?.close() }
    }

    private companion object {
        private const val TAG = "DesktopCallSound"
    }
}
