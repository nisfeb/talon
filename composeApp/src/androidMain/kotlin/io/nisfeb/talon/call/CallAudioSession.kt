package io.nisfeb.talon.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import io.nisfeb.talon.util.Log

/**
 * Process-wide refcount over "a voice session is live".
 *
 * A 1:1 call and a party line can overlap, and each used to snapshot
 * `audioManager.mode` at construction and restore it on close — so
 * whichever closed first restored the wrong mode (leaving a party line
 * mid-call snapped the phone back to NORMAL and broke voice routing;
 * other orderings stuck it in MODE_IN_COMMUNICATION forever). The fix
 * is to stop snapshotting: the first session in sets communication
 * mode, the last one out sets NORMAL — the world's resting state, not
 * whatever mode we happened to observe at construction.
 *
 * The same last-one-out moment is where the other per-call cleanup
 * belongs: abandoning audio focus (without which Spotify plays at full
 * volume under every call) and clearing the communication device, so a
 * speakerphone pick in one call doesn't open the next call — hours
 * later, in public — on speaker.
 */
internal object CallAudioSession {

    private var count = 0
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    // Log only: ending the call when something steals focus (a phone
    // call interrupting us) is v-next; for now the OS mixes as it sees
    // fit and we keep the session up.
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        Log.i(TAG, "audio focus change: $change")
    }

    @Synchronized
    fun acquire(context: Context) {
        if (++count > 1) return
        val am = context.applicationContext
            .getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = am
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = request
        if (am.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            // Denied focus is a degraded call (music keeps playing),
            // not a failed one — proceed.
            Log.w(TAG, "audio focus not granted; proceeding without it")
        }
    }

    @Synchronized
    fun release() {
        if (count == 0) {
            // Callers' closed-guards should make this unreachable;
            // don't let a stray release push the count negative.
            Log.w(TAG, "release without acquire — ignoring")
            return
        }
        if (--count > 0) return
        val am = audioManager ?: return
        focusRequest?.let { runCatching { am.abandonAudioFocusRequest(it) } }
        focusRequest = null
        am.mode = AudioManager.MODE_NORMAL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The docs require clearing the communication device when
            // the call ends; same API-31 floor as setCommunicationDevice
            // in AndroidAudioDevices.
            runCatching { am.clearCommunicationDevice() }
        }
        AndroidAudioDevices.clearSelection()
    }

    private const val TAG = "CallAudioSession"
}
