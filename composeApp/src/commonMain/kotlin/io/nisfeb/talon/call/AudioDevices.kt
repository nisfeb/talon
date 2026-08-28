package io.nisfeb.talon.call

/**
 * One selectable audio device.
 *
 * [id] is whatever the platform uses to name a device to itself and is
 * never shown; [label] is. They are separate because the platform
 * identifiers are unreadable (ALSA descriptors, Core Audio UIDs) and
 * because a label alone is not unique — two identical headsets produce
 * two identical labels.
 */
data class AudioDevice(val id: String, val label: String)

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

    /** False on platforms where the OS owns routing. The settings pane
     *  is hidden entirely rather than shown empty or disabled. */
    val supported: Boolean get() = false

    fun inputs(): List<AudioDevice> = emptyList()

    fun outputs(): List<AudioDevice> = emptyList()

    /** Currently chosen device id, or null for "whatever the system
     *  picks" — which is the right default and must stay reachable. */
    val selectedInput: String? get() = null
    val selectedOutput: String? get() = null

    /** Pass null to hand the choice back to the system. */
    fun selectInput(id: String?) = Unit
    fun selectOutput(id: String?) = Unit

    companion object {
        val Noop: AudioDevices = object : AudioDevices {}
    }
}
