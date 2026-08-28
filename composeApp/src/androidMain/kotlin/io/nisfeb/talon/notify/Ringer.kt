package io.nisfeb.talon.notify

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import io.nisfeb.talon.util.Log

/**
 * Rings, for as long as a phone call rings.
 *
 * A notification channel's sound and vibration each fire **once** — the
 * channel carrying the ringtone URI is what makes the first blip sound
 * like a call, not what makes it repeat, and the default vibration
 * pattern is the two buzzes people were reporting as the whole ring.
 * Android has no "keep ringing" flag; a calling app loops it itself.
 *
 * Stops on the same paths that clear the notification — answered,
 * declined, or the caller gave up — and on its own after the ring
 * timeout, so a missed call can never leave the phone buzzing.
 */
object Ringer {

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var stopAt: Runnable? = null

    /**
     * @param forMs stop after this long no matter what. Matches the
     *   caller's own ring timeout: past that there is nobody there.
     */
    @Synchronized
    fun start(context: Context, forMs: Long) {
        stop()
        val app = context.applicationContext
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        // Honour the physical switch. Ringing a silenced phone is the
        // single rudest thing an app can do.
        val mode = audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        if (mode == AudioManager.RINGER_MODE_SILENT) return

        if (mode == AudioManager.RINGER_MODE_NORMAL) startSound(app)
        startVibration(app)

        stopAt = Runnable { stop() }.also { main.postDelayed(it, forMs) }
    }

    @Synchronized
    fun stop() {
        stopAt?.let { main.removeCallbacks(it) }
        stopAt = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { vibrator?.cancel() }
        vibrator = null
    }

    private fun startSound(app: Context) {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: return
        runCatching {
            // MediaPlayer rather than Ringtone: looping on Ringtone
            // only arrived in API 28, and this has to work from 26.
            player = MediaPlayer().apply {
                setDataSource(app, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                isLooping = true
                prepare()
                start()
            }
        }.onFailure {
            Log.w(TAG, "could not start the ringtone", it)
            player = null
        }
    }

    private fun startVibration(app: Context) {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        if (!v.hasVibrator()) return
        vibrator = v
        runCatching {
            // Repeat from index 0, which is what makes it a ring rather
            // than a buzz: wait, buzz, gap, forever until cancelled.
            v.vibrate(VibrationEffect.createWaveform(PATTERN, 0))
        }.onFailure {
            Log.w(TAG, "could not start vibration", it)
            vibrator = null
        }
    }

    private const val TAG = "Ringer"

    /** off, on, off — the cadence of a phone ringing. */
    private val PATTERN = longArrayOf(0, 800, 1200)
}
