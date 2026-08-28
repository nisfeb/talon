package io.nisfeb.talon.call

import io.nisfeb.talon.util.Log
import io.nisfeb.talon.util.toNSData
import platform.AVFAudio.AVAudioPlayer

/**
 * Call tones on iOS.
 *
 * AVAudioPlayer takes a container rather than raw samples, so the PCM
 * is wrapped in a WAV in commonMain and handed over as NSData. That
 * avoids AVAudioPCMBuffer, which would mean filling channel data
 * through a cinterop pointer for no gain.
 *
 * Looping is the player's own: numberOfLoops = -1 repeats until
 * stopped, so unlike desktop and Android this needs no thread. It
 * repeats with no gap between passes, so the gap is baked into the
 * buffer instead — same cadence, less machinery.
 *
 * The audio session is left alone deliberately. TalonRtc configures it
 * for voice chat when a call starts, and a tone reconfiguring it
 * underneath a live call is a good way to drop the call's audio.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class IosCallSoundPlayer : CallSoundPlayer {

    private var oneShot: AVAudioPlayer? = null
    private var looper: AVAudioPlayer? = null

    override fun play(pcm: ByteArray) {
        runCatching {
            oneShot?.stop()
            oneShot = make(pcm, loops = 0)?.also { it.play() }
        }.onFailure { Log.i(TAG, "could not play a tone: ${it.message}") }
    }

    override fun loop(pcm: ByteArray, gapMs: Int) {
        runCatching {
            stopLoop()
            // The gap rides in the buffer: AVAudioPlayer repeats
            // seamlessly, which for a ring would be a solid drone.
            looper = make(CallSounds.withGap(pcm, gapMs), loops = -1)?.also { it.play() }
        }.onFailure { Log.i(TAG, "could not start ringing: ${it.message}") }
    }

    override fun stopLoop() {
        runCatching { looper?.stop() }
        looper = null
    }

    private fun make(pcm: ByteArray, loops: Int): AVAudioPlayer? {
        val player = AVAudioPlayer(data = CallSounds.wav(pcm).toNSData(), error = null)
        player.numberOfLoops = loops.toLong()
        return if (player.prepareToPlay()) player else null
    }

    private companion object {
        private const val TAG = "IosCallSound"
    }
}
