package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.nisfeb.talon.call.AudioDevices
import io.nisfeb.talon.call.AudioRouteKind
import io.nisfeb.talon.call.CallProximity
import io.nisfeb.talon.call.CallUiState
import io.nisfeb.talon.call.PartyState
import io.nisfeb.talon.call.SpeakerButton
import io.nisfeb.talon.call.proximityWanted
import io.nisfeb.talon.call.speakerButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A call's speaker button as drawn: a toggle or the route list, whether the speaker is on, and what a press does. */
internal class SpeakerUi(val pick: Boolean, val on: Boolean, val press: () -> Unit)

/**
 * The speaker button for a call or a line, or null where there is none:
 * a computer, an iPad, a phone whose only route is its speaker. Drawn
 * from the route the platform reports, so a pick it refused, or a
 * headset taking the sound, shows as it is. [onPick] opens the route
 * list, for a phone with a headset in as well.
 */
@Composable
internal fun rememberSpeaker(devices: AudioDevices, onPick: () -> Unit): SpeakerUi? {
    val route by devices.route.collectAsState()
    // Read again as the route moves: a headset in or out changes the list.
    val button = remember(devices, route) { speakerButton(devices.outputs()) }
    val on = route == AudioRouteKind.Speaker
    return when (button) {
        SpeakerButton.None -> null
        SpeakerButton.Pick -> SpeakerUi(pick = true, on = on, press = onPick)
        is SpeakerButton.Toggle -> SpeakerUi(pick = false, on = on) {
            // Off picks the other route by name: "the system's choice" did
            // nothing on Android's newer telecom and could never pick Bluetooth.
            devices.selectOutput(if (on) button.other.id else button.speaker.id)
        }
    }
}

private val idleCall: StateFlow<CallUiState> = MutableStateFlow(CallUiState.None)
private val idleParty: StateFlow<PartyState> = MutableStateFlow(PartyState.Idle)

/**
 * The screen off at the ear while a call or a line has its sound in the
 * earpiece ([proximityWanted]). One function for both shells, as the
 * font repo is. Let go as it leaves the composition, so the lock goes
 * with the shell that held it.
 */
@Composable
fun CallProximityEffect(
    call: StateFlow<CallUiState>?,
    party: StateFlow<PartyState>?,
    devices: AudioDevices,
    proximity: CallProximity,
) {
    val c by (call ?: idleCall).collectAsState()
    // Only whether the line is live: its state moves whenever anyone speaks.
    val live by remember(party) { io.nisfeb.talon.util.mapState(party ?: idleParty) { it is PartyState.Live } }.collectAsState()
    val route by devices.route.collectAsState()
    val want = proximityWanted(c, live, route)
    DisposableEffect(want, proximity) {
        proximity.screenOffNearFace(want)
        onDispose { proximity.screenOffNearFace(false) }
    }
}
