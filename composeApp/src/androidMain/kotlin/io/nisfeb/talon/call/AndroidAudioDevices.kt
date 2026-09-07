package io.nisfeb.talon.call

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import io.nisfeb.talon.util.Log

/**
 * Call routing on Android.
 *
 * Android routes a *call* to one device rather than pairing a
 * microphone with a speaker, so this reports [unifiedRoute] and the UI
 * shows a single list. `setCommunicationDevice` is the whole API: it
 * moves capture and playback together and is the only supported way to
 * do this since API 31.
 *
 * Below API 31 the equivalent is a pile of deprecated toggles —
 * `isSpeakerphoneOn`, `startBluetoothSco` — that don't enumerate and
 * behave differently per OEM. Rather than ship something that half
 * works and lies about what it did, [supported] is false there and the
 * pane doesn't appear; the system's own call routing still applies.
 */
class AndroidAudioDevices(context: Context) : AudioDevices {

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Set while a call is registered with telecom, which then owns
     *  routing; the list and the choice come from it. See [TelecomRoute]. */
    @Volatile
    var telecom: TelecomRoute? = null
    private val viaTelecom: TelecomRoute? get() = telecom?.takeIf { it.active }

    override val supported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || viaTelecom != null

    override val unifiedRoute: Boolean = true

    override val selectedInput: String? get() = viaTelecom?.selected ?: chosen
    override val selectedOutput: String? get() = viaTelecom?.selected ?: chosen

    /**
     * Available call routes.
     *
     * Keyed by AudioDeviceInfo.id as a string. The id is stable only
     * while the device stays connected, which is fine — the list is
     * re-read every time the pane opens, and a device that has gone
     * away should stop being offered.
     */
    override fun inputs(): List<AudioDevice> {
        viaTelecom?.let { return it.devices() }
        if (!supported) return emptyList()
        return runCatching {
            audioManager.availableCommunicationDevices.map { d ->
                AudioDevice(id = d.id.toString(), label = labelFor(d))
            }
        }.getOrElse {
            Log.w(TAG, "could not list communication devices", it)
            emptyList()
        }
    }

    /** Same list: one route serves both directions. */
    override fun outputs(): List<AudioDevice> = inputs()

    override fun selectInput(id: String?) = select(id)

    override fun selectOutput(id: String?) = select(id)

    private fun select(id: String?) {
        viaTelecom?.let { it.select(id); return }
        if (!supported) return
        runCatching {
            if (id == null) {
                // Hand routing back to the platform rather than leaving
                // the last pick in force.
                audioManager.clearCommunicationDevice()
                chosen = null
                Log.i(TAG, "audio route -> system default")
                return
            }
            val target = audioManager.availableCommunicationDevices
                .firstOrNull { it.id.toString() == id }
            if (target == null) {
                Log.w(TAG, "route $id is gone; leaving routing alone")
                return
            }
            // Returns false when the platform refuses — a headset
            // mid-disconnect, or a device another app holds. Don't
            // record a selection that didn't happen.
            if (audioManager.setCommunicationDevice(target)) {
                chosen = id
                Log.i(TAG, "audio route -> ${labelFor(target)}")
            } else {
                Log.w(TAG, "platform refused route ${labelFor(target)}")
            }
        }.onFailure { Log.w(TAG, "could not set audio route", it) }
    }

    /**
     * A name a person recognises.
     *
     * productName is the Bluetooth device's own name where there is
     * one ("WH-1000XM4"), which beats "Bluetooth headset"; the type
     * name is the fallback for built-ins, which report the phone's
     * model and would read as three identically-named rows.
     */
    private fun labelFor(d: AudioDeviceInfo): String = when (d.type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET ->
            d.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth headset"
        AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing aid"
        else -> d.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Audio device"
    }

    companion object {
        private const val TAG = "AndroidAudioDevices"

        // Companion, not instance state: setCommunicationDevice is
        // process-global, so the record of what we picked must be too.
        @Volatile
        private var chosen: String? = null

        /**
         * Forget the recorded route. [CallAudioSession] calls this when
         * the last call/line ends, right after clearCommunicationDevice
         * hands routing back to the platform — so the pane doesn't show
         * a selection that is no longer in force.
         */
        internal fun clearSelection() {
            chosen = null
        }
    }
}
