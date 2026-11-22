package io.nisfeb.talon.call

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One selectable audio device.
 *
 * [id] is whatever the platform uses to name a device to itself and is
 * never shown; [label] is. They are separate because the platform
 * identifiers are unreadable (ALSA descriptors, Core Audio UIDs) and
 * because a label alone is not unique — two identical headsets produce
 * two identical labels.
 */
data class AudioDevice(
    val id: String,
    val label: String,
    /** Which of a phone's own two a route is, from the platform's type, never the label. */
    val kind: AudioRouteKind = AudioRouteKind.Other,
)

/** A phone's earpiece, its loudspeaker, or anything else (a headset, a computer's devices). */
enum class AudioRouteKind { Earpiece, Speaker, Other }

/**
 * Picking the microphone and speaker for a call.
 *
 * Deliberately a plain interface with a [Noop] default rather than
 * expect/actual: only desktop presents audio as a *list of devices*.
 * Phones present a short list of routes — earpiece, speaker, whatever
 * bluetooth thing is paired — owned by the OS, and on iOS Apple wants
 * the system route picker rather than an app's own menu. Modelling
 * those as this interface would be pretending they are the same thing.
 *
 * A leaf that can't do this wires [Noop] and the UI hides itself, per
 * the capability rule in CLAUDE.md: gate it, don't fake it.
 */
interface AudioDevices {

    /** False where the platform offers no choice at all. The settings
     *  pane is hidden entirely rather than shown empty or disabled. */
    val supported: Boolean get() = false

    /**
     * True when one choice covers both directions.
     *
     * Phones route a *call* to a device — earpiece, speaker, the
     * headset — rather than picking a microphone and a speaker
     * separately, and both Android's communication device and iOS's
     * preferred input work that way. Desktop is the odd one out with
     * genuinely independent capture and render devices.
     *
     * The UI shows one list instead of two when this is set; showing
     * two that secretly move together would be a lie.
     */
    val unifiedRoute: Boolean get() = false

    fun inputs(): List<AudioDevice> = emptyList()

    fun outputs(): List<AudioDevice> = emptyList()

    /** Currently chosen device id, or null for "whatever the system
     *  picks" — which is the right default and must stay reachable. */
    val selectedInput: String? get() = null
    val selectedOutput: String? get() = null

    /** Pass null to hand the choice back to the system. */
    fun selectInput(id: String?) = Unit
    fun selectOutput(id: String?) = Unit

    /**
     * Where the call's sound goes now, as the platform reports it: it
     * moves when the platform moves it (a headset plugged in, a pick it
     * refused), not when a button is pressed. Other where unknown.
     */
    val route: StateFlow<AudioRouteKind> get() = NO_ROUTE

    companion object {
        val Noop: AudioDevices = object : AudioDevices {}
        private val NO_ROUTE: StateFlow<AudioRouteKind> = MutableStateFlow(AudioRouteKind.Other)
    }
}

/** What a call's speaker button is, for the routes a phone offers. */
sealed interface SpeakerButton {
    /** No loudspeaker to go to, or nothing to come back to: no button. */
    data object None : SpeakerButton

    /** Two routes, the speaker and one other: the button flips between them. */
    data class Toggle(val speaker: AudioDevice, val other: AudioDevice) : SpeakerButton

    /** A headset in as well: the button opens the list, as a phone's dialer does. */
    data object Pick : SpeakerButton
}

/** The button for [routes]: none without a speaker and somewhere else, a toggle with two, the list with more. */
fun speakerButton(routes: List<AudioDevice>): SpeakerButton {
    val speaker = routes.firstOrNull { it.kind == AudioRouteKind.Speaker } ?: return SpeakerButton.None
    val others = routes.filter { it.id != speaker.id }
    return when (others.size) {
        0 -> SpeakerButton.None
        1 -> SpeakerButton.Toggle(speaker, others.single())
        else -> SpeakerButton.Pick
    }
}

/**
 * The screen off while the phone is at the ear: on a call or a line,
 * with its sound in the earpiece. Not while one rings in, when the
 * screen is how it is answered, and not on the speaker or a headset.
 */
interface CallProximity {
    /** Hold the screen off near the face, or let it go. Called again with the same value is nothing. */
    fun screenOffNearFace(on: Boolean)

    companion object {
        val Noop: CallProximity = object : CallProximity {
            override fun screenOffNearFace(on: Boolean) = Unit
        }
    }
}

/** Whether [CallProximity] holds: placing a call, on one, or on a line, with the sound in the earpiece. */
fun proximityWanted(call: CallUiState, onLine: Boolean, route: AudioRouteKind): Boolean =
    route == AudioRouteKind.Earpiece && (call is CallUiState.Active || call is CallUiState.Outgoing || onLine)
