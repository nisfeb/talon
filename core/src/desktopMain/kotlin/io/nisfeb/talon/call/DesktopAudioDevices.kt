package io.nisfeb.talon.call

import dev.onvoid.webrtc.media.MediaDevices
import io.nisfeb.talon.util.Log

/**
 * Desktop microphone and speaker selection, via libwebrtc's own device
 * enumeration.
 *
 * Selection is applied to the process-wide AudioDeviceModule that
 * [DesktopWebRtcFactory] built its factory with. It has to be that
 * instance: an ADM created here would enumerate the same devices and
 * change nothing, because the tracks are sourced from the factory's.
 *
 * Enumeration is a native call, so results are cached and refreshed
 * when the OS reports a device change — a dropdown that re-enumerates
 * on every recomposition would hit the driver on every frame.
 */
class DesktopAudioDevices : AudioDevices {

    override val supported: Boolean = true

    private var inputCache: List<AudioDevice>? = null
    private var outputCache: List<AudioDevice>? = null

    private var input: String? = null
    private var output: String? = null

    override val selectedInput: String? get() = input
    override val selectedOutput: String? get() = output

    init {
        runCatching {
            MediaDevices.addDeviceChangeListener(
                object : dev.onvoid.webrtc.media.DeviceChangeListener {
                    override fun deviceConnected(device: dev.onvoid.webrtc.media.Device?) = invalidate()
                    override fun deviceDisconnected(device: dev.onvoid.webrtc.media.Device?) = invalidate()
                },
            )
        }.onFailure { Log.w(TAG, "no device-change notifications", it) }
    }

    private fun invalidate() {
        inputCache = null
        outputCache = null
    }

    override fun inputs(): List<AudioDevice> = inputCache ?: enumerate(capture = true)
        .also { inputCache = it }

    override fun outputs(): List<AudioDevice> = outputCache ?: enumerate(capture = false)
        .also { outputCache = it }

    private fun enumerate(capture: Boolean): List<AudioDevice> = runCatching {
        val devices =
            if (capture) MediaDevices.getAudioCaptureDevices()
            else MediaDevices.getAudioRenderDevices()
        devices.orEmpty().mapNotNull { d ->
            val id = d?.descriptor ?: return@mapNotNull null
            AudioDevice(id = id, label = d.name?.takeIf { it.isNotBlank() } ?: id)
        }
    }.getOrElse {
        Log.w(TAG, "audio device enumeration failed", it)
        emptyList()
    }

    override fun selectInput(id: String?) {
        input = id
        apply(id, capture = true)
    }

    override fun selectOutput(id: String?) {
        output = id
        apply(id, capture = false)
    }

    /**
     * Null means "system default", which libwebrtc has no explicit call
     * for — ask MediaDevices what the default is and set that, rather
     * than leaving whatever was last chosen in place.
     */
    private fun apply(id: String?, capture: Boolean) {
        runCatching {
            val adm = DesktopWebRtcFactory.audioDeviceModule()
            val list =
                if (capture) MediaDevices.getAudioCaptureDevices()
                else MediaDevices.getAudioRenderDevices()
            val target = id?.let { want -> list.orEmpty().firstOrNull { it?.descriptor == want } }
                ?: if (capture) MediaDevices.getDefaultAudioCaptureDevice()
                else MediaDevices.getDefaultAudioRenderDevice()
            if (target == null) {
                Log.w(TAG, "no device matched ${id ?: "(default)"}")
                return
            }
            if (capture) adm.setRecordingDevice(target) else adm.setPlayoutDevice(target)
            Log.i(TAG, "audio ${if (capture) "input" else "output"} -> ${target.name}")
        }.onFailure {
            // A device can vanish between listing and selecting. Keep
            // the call alive on whatever is already open rather than
            // taking the app down over a dropdown.
            Log.w(TAG, "could not set audio device", it)
        }
    }

    private companion object {
        private const val TAG = "DesktopAudioDevices"
    }
}
