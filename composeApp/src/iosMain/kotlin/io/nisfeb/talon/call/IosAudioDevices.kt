package io.nisfeb.talon.call

import io.nisfeb.talon.util.Log
import platform.AVFAudio.AVAudioSession
// availableInputs is an extension in these bindings, so it needs
// an explicit import; setPreferredInput is a member and must not be
// imported at all.
import platform.AVFAudio.availableInputs
import platform.AVFAudio.AVAudioSessionPortBuiltInMic
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.AVAudioSessionPortOverrideNone
import platform.AVFAudio.AVAudioSessionPortOverrideSpeaker
import platform.AVFAudio.AVAudioSessionPortBuiltInReceiver
import platform.AVFAudio.AVAudioSessionPortBuiltInSpeaker
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.currentRoute
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomPhone

/**
 * Call routing on iOS.
 *
 * Reached through AVAudioSession rather than a Swift bridge: the
 * bindings are already in the Kotlin/Native platform libs, and routing
 * has nothing to do with the WebRTC peer that lives in the Xcode
 * target.
 *
 * iOS routes a call to one place, so this is a [unifiedRoute]. Picking
 * a headset or a Bluetooth device sets it as the preferred *input* and
 * playback follows it; "Speaker" is not an input at all, so it is a
 * synthetic entry that overrides the output port and leaves the
 * built-in mic capturing. That asymmetry is iOS's, not ours.
 *
 * Deliberately not AVRoutePickerView: it is a UIKit view that has to be
 * placed in the Xcode target, and it only offers *outputs*. The list
 * here is what a person actually wants to choose between.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class IosAudioDevices : AudioDevices {

    override val supported: Boolean = true
    override val unifiedRoute: Boolean = true

    private var chosen: String? = null

    override val selectedInput: String? get() = chosen
    override val selectedOutput: String? get() = chosen

    private val session get() = AVAudioSession.sharedInstance()

    /** An iPhone has an earpiece; an iPad's own route is its speaker, so it gets no speaker button. */
    private val phone = UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPhone

    private val _route = kotlinx.coroutines.flow.MutableStateFlow(AudioRouteKind.Other)
    override val route: kotlinx.coroutines.flow.StateFlow<AudioRouteKind> = _route

    init {
        // The session's own word: a speaker override is dropped when a
        // headset comes or goes, and a pick can be refused.
        NSNotificationCenter.defaultCenter.addObserverForName(
            AVAudioSessionRouteChangeNotification, null, NSOperationQueue.mainQueue,
        ) { _ -> refreshRoute() }
        refreshRoute()
    }

    private fun refreshRoute() {
        _route.value = runCatching {
            val out = session.currentRoute.outputs.filterIsInstance<AVAudioSessionPortDescription>().firstOrNull()?.portType
            when (out) {
                AVAudioSessionPortBuiltInReceiver -> AudioRouteKind.Earpiece
                AVAudioSessionPortBuiltInSpeaker -> AudioRouteKind.Speaker
                else -> AudioRouteKind.Other
            }
        }.getOrDefault(AudioRouteKind.Other)
    }

    override fun inputs(): List<AudioDevice> = runCatching {
        val ports = session.availableInputs
            ?.filterIsInstance<AVAudioSessionPortDescription>()
            .orEmpty()
            .map {
                // The built-in mic is the earpiece's route: chosen, the sound
                // goes back to the receiver (an iPhone's), not the speaker.
                val own = phone && it.portType == AVAudioSessionPortBuiltInMic
                AudioDevice(id = it.UID, label = it.portName, kind = if (own) AudioRouteKind.Earpiece else AudioRouteKind.Other)
            }
        // The speaker is an output, so it never appears in
        // availableInputs — but it is the one route people reach for
        // most on a phone, and leaving it out would make the list look
        // broken.
        ports + AudioDevice(id = SPEAKER_ID, label = "Speaker", kind = if (phone) AudioRouteKind.Speaker else AudioRouteKind.Other)
    }.getOrElse {
        Log.w(TAG, "could not list audio routes", it)
        emptyList()
    }

    override fun outputs(): List<AudioDevice> = inputs()

    override fun selectInput(id: String?) = select(id)

    override fun selectOutput(id: String?) = select(id)

    private fun select(id: String?) {
        runCatching {
            when (id) {
                null -> {
                    // Back to whatever iOS would pick on its own.
                    session.overrideOutputAudioPort(AVAudioSessionPortOverrideNone, null)
                    session.setPreferredInput(null, null)
                    chosen = null
                    Log.i(TAG, "audio route -> system default")
                }
                SPEAKER_ID -> {
                    // Speaker is an output override; capture stays on
                    // the built-in mic, which is what a speakerphone
                    // is.
                    session.availableInputs
                        ?.filterIsInstance<AVAudioSessionPortDescription>()
                        ?.firstOrNull { it.portType == AVAudioSessionPortBuiltInMic }
                        ?.let { session.setPreferredInput(it, null) }
                    session.overrideOutputAudioPort(AVAudioSessionPortOverrideSpeaker, null)
                    chosen = id
                    Log.i(TAG, "audio route -> speaker")
                }
                else -> {
                    val port = session.availableInputs
                        ?.filterIsInstance<AVAudioSessionPortDescription>()
                        ?.firstOrNull { it.UID == id }
                    if (port == null) {
                        Log.w(TAG, "route $id is gone; leaving routing alone")
                        return
                    }
                    // Clear any speaker override first, or playback
                    // stays on the speaker while capture moves to the
                    // headset — audible as an echo to the far end.
                    session.overrideOutputAudioPort(AVAudioSessionPortOverrideNone, null)
                    session.setPreferredInput(port, null)
                    chosen = id
                    Log.i(TAG, "audio route -> ${port.portName}")
                }
            }
        }.onFailure { Log.w(TAG, "could not set audio route", it) }
        refreshRoute()
    }

    private companion object {
        private const val TAG = "IosAudioDevices"

        /** Not a real port UID; the speaker is an output override. */
        private const val SPEAKER_ID = "__talon_speaker"
    }
}
