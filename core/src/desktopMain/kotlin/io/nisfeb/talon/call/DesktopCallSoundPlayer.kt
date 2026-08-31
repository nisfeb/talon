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
        // The loop condition is ownership, not just the flag: a stopped
        // thread stuck in line.write outlives its 300ms join, and a
        // shared flag would resurrect it when the next loop() sets
        // `looping` back — two rings at once. start=false so loopThread
        // is assigned before the body's first ownership check.
        val t = thread(isDaemon = true, name = "talon-ring", start = false) {
            val self = Thread.currentThread()
            while (looping && loopThread === self) {
                writeOnce(pcm, loop = true)
                // Sleep in slices so stopping is felt immediately
                // rather than after a four-second gap.
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
        // Null the thread before the flag: an orphan checks ownership,
        // so it dies at its next check even if a new loop() has already
        // set `looping` true again.
        val t = loopThread
        loopThread = null
        looping = false
        t?.let { runCatching { it.join(300) } }
    }

    private fun writeOnce(pcm: ByteArray, loop: Boolean = false) {
        var line: SourceDataLine? = null
        runCatching {
            line = AudioSystem.getSourceDataLine(format).apply {
                // A small buffer, or the stop check below is theater:
                // the default holds ~half a second, writes race that
                // far ahead of playback, and a stopped ring keeps
                // sounding out of the buffer.
                open(format, 8192)
                start()
            }
            // Write in chunks and re-check between them, so a ring that
            // is answered mid-tone stops now rather than finishing.
            var off = 0
            val chunk = 4096
            var cut = false
            while (off < pcm.size) {
                if (loop && (!looping || loopThread !== Thread.currentThread())) {
                    cut = true
                    break
                }
                val n = minOf(chunk, pcm.size - off)
                line!!.write(pcm, off, n)
                off += n
            }
            // drain would play out what's buffered — an answered ring
            // must go silent now, so a cut discards it instead.
            if (cut) line!!.flush() else line!!.drain()
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
