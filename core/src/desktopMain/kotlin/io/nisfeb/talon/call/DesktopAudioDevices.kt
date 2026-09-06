package io.nisfeb.talon.call

import dev.onvoid.webrtc.media.MediaDevices
import dev.onvoid.webrtc.media.audio.AudioDeviceModuleBase
import dev.onvoid.webrtc.media.audio.AudioDevice as WebRtcAudioDevice
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

    // Volatile: invalidate() runs on webrtc-java's native device-change
    // thread while the UI thread reads — without it a stale list can
    // survive unplugging a headset.
    @Volatile private var inputCache: List<AudioDevice>? = null
    @Volatile private var outputCache: List<AudioDevice>? = null

    @Volatile private var input: String? = null
    @Volatile private var output: String? = null

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
        if (apply(id, previous = input, capture = true)) {
            input = id
            // The recording tap opens its own AudioRecorder, so it can't
            // see what we just told the shared module to use. Publish it
            // or a recording captures whatever device happens to be
            // enumerated first, not the mic the call is on.
            DesktopWebRtcFactory.preferredRecordingDeviceId = id
        }
    }

    override fun selectOutput(id: String?) {
        if (apply(id, previous = output, capture = false)) output = id
    }

    /**
     * Null means "system default", which libwebrtc has no explicit call
     * for — ask MediaDevices what the default is and set that, rather
     * than leaving whatever was last chosen in place.
     *
     * Returns whether [id] is now the active device; the caller only
     * commits its selection on true, so the picker never shows a device
     * that isn't actually in use.
     */
    // Set while a side sits stopped because a dance died between its
    // stop and start halves. A later selection must dance that side
    // back to life even though the bare set now succeeds (a stopped
    // side is uninitialized, so the bare set is no proof of health).
    @Volatile private var captureBroken = false
    @Volatile private var playoutBroken = false

    private fun apply(id: String?, previous: String?, capture: Boolean): Boolean = runCatching {
        val adm = DesktopWebRtcFactory.audioDeviceModule()
        // Strict when the user named a device: falling back to the
        // default here made a vanished-device click "succeed" and
        // commit the dead descriptor as selected.
        val target = if (id != null) findExact(id, capture) else findDefault(capture)
        if (target == null) {
            Log.w(TAG, "no device matched ${id ?: "(default)"}")
            return false
        }
        // A bare set is all libwebrtc allows while the side is idle;
        // once initialized — i.e. during any live call — it refuses,
        // and the stop/set/init/start dance is the only way to switch.
        // Idle must not dance: startRecording would hold the mic open
        // (and its OS indicator lit) with no call running.
        // ponytail: a hang-up racing this exact click can end the call
        // between the failed bare set and the dance's start half,
        // leaving the side running while idle until the next call's
        // init resets it — plumb call state in here if that ever bites.
        val bareSet = runCatching {
            if (capture) adm.setRecordingDevice(target) else adm.setPlayoutDevice(target)
        }
        if (bareSet.isFailure || (if (capture) captureBroken else playoutBroken)) {
            runCatching { restart(adm, target, capture) }.onFailure { e ->
                // The stop half already ran, so this side of the call
                // is silent: best effort to bring the old device back
                // (previous == null restores the system default).
                val prev = previous?.let { findExact(it, capture) } ?: findDefault(capture)
                prev?.let { runCatching { restart(adm, it, capture) } }
                throw e
            }
        }
        Log.i(TAG, "audio ${if (capture) "input" else "output"} -> ${target.name}")
        true
    }.getOrElse {
        // A device can vanish between listing and selecting. Keep
        // the call alive on whatever is already open rather than
        // taking the app down over a dropdown.
        Log.w(TAG, "could not set audio device", it)
        false
    }

    private fun restart(adm: AudioDeviceModuleBase, target: WebRtcAudioDevice, capture: Boolean) {
        if (capture) {
            captureBroken = true
            adm.stopRecording()
            adm.setRecordingDevice(target)
            adm.initRecording()
            adm.startRecording()
            captureBroken = false
        } else {
            playoutBroken = true
            adm.stopPlayout()
            adm.setPlayoutDevice(target)
            adm.initPlayout()
            adm.startPlayout()
            playoutBroken = false
        }
    }

    private fun findExact(id: String, capture: Boolean): WebRtcAudioDevice? {
        val list =
            if (capture) MediaDevices.getAudioCaptureDevices()
            else MediaDevices.getAudioRenderDevices()
        return list.orEmpty().firstOrNull { it?.descriptor == id }
    }

    private fun findDefault(capture: Boolean): WebRtcAudioDevice? =
        if (capture) MediaDevices.getDefaultAudioCaptureDevice()
        else MediaDevices.getDefaultAudioRenderDevice()

    private companion object {
        private const val TAG = "DesktopAudioDevices"
    }
}
