package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import io.ktor.client.HttpClient
import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.call.CallController
import io.nisfeb.talon.call.PartyLine
import io.nisfeb.talon.call.RecordedCall
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/** The recording controls a party bar needs: our state, who's recording
 *  (the room-wide badge), and the toggle (null when unsupported). */
data class PartyRecordingControls(
    val recording: Boolean,
    val recordedBy: Set<String>,
    val onToggleRecord: (() -> Unit)?,
)

/**
 * The whole call-recording orchestration for a party line, in one place
 * so both app roots (desktop/iOS App.kt and Android TalonApp.kt) share
 * it instead of each re-deriving it. Polls who's recording (the badge),
 * heartbeats our recording to the host while active with a stop on the
 * way out, and renders the first-time consent dialog and the post-record
 * publish/save dialog. Returns the controls to hand to [PartyLineBar].
 *
 * Ship URL and cookie come from [LocalShipUrl] / [LocalShipCookie], so
 * callers only pass what they can't.
 */
@Composable
fun rememberPartyRecording(
    callController: CallController?,
    partyLine: PartyLine?,
    partyRoomHere: Pair<String, String>?,
    onThisLine: Boolean,
    http: HttpClient,
    aiConfig: AiSettings.Config,
    ourShip: String,
    conversationTitle: String,
    nameFor: (String) -> String,
): PartyRecordingControls {
    // Who is recording (badge), polled like presence.
    val recordingMapFlow = remember(callController) {
        callController?.recording ?: MutableStateFlow(emptyMap())
    }
    val recordingMap by recordingMapFlow.collectAsState()
    val recordedBy = partyRoomHere?.let { (h, n) -> recordingMap["$h/$n"] } ?: emptySet()
    LaunchedEffect(partyRoomHere, onThisLine) {
        val (h, n) = partyRoomHere ?: return@LaunchedEffect
        val cc = callController ?: return@LaunchedEffect
        if (!onThisLine) return@LaunchedEffect
        while (true) {
            cc.recordersOf(h, n)
            delay(15_000)
        }
    }

    // Both of these are read from the engine, not held here. This
    // composable lives in the chat slot, so it is disposed by opening a
    // DM, a thread, Group Info or Settings — when it owned the state,
    // that cleared the room-wide badge while the taps kept running, and
    // leaving the line stranded the capture with no reachable Stop.
    val recordingFlow = remember(partyLine) {
        partyLine?.recording ?: MutableStateFlow(false)
    }
    val recordingNow by recordingFlow.collectAsState()
    val lastRecordingFlow = remember(partyLine) {
        partyLine?.lastRecording ?: MutableStateFlow<RecordedCall?>(null)
    }
    val recordedResult by lastRecordingFlow.collectAsState()

    var recordConsented by remember { mutableStateOf(false) }
    var showRecordConsent by remember { mutableStateOf(false) }

    fun beginRecording() {
        val line = partyLine ?: return
        line.startRecording(ourShip)
        // Announce to the line we are actually on, captured now. The
        // heartbeat runs on the controller's scope so navigating away
        // no longer retracts it.
        partyRoomHere?.let { (h, n) -> callController?.beginRecordingAnnounce(h, n) }
    }

    val canRecord = isCallRecordingSupported &&
        onThisLine && partyLine != null && ourShip.isNotEmpty()
    val onToggleRecord: (() -> Unit)? = if (canRecord) {
        {
            val line = partyLine!!
            if (recordingNow) {
                line.stopRecording() // publishes to lastRecording
                callController?.endRecordingAnnounce()
            } else if (!recordConsented) {
                showRecordConsent = true
            } else {
                beginRecording()
            }
        }
    } else {
        null
    }

    RecordConsentDialog(
        show = showRecordConsent,
        onDismiss = { showRecordConsent = false },
        onConfirm = {
            recordConsented = true
            showRecordConsent = false
            beginRecording()
        },
    )
    recordedResult?.let { rec ->
        RecordingResultDialog(
            rec = rec,
            http = http,
            sttConfig = aiConfig,
            shipUrl = LocalShipUrl.current,
            cookie = LocalShipCookie.current,
            ourShip = ourShip,
            title = conversationTitle,
            nameFor = nameFor,
            onClose = {
                partyLine?.clearLastRecording()
                // A recording finalized by teardown (leaving the line)
                // never went through the toggle, so retract the badge here.
                callController?.endRecordingAnnounce()
            },
        )
    }

    return PartyRecordingControls(recordingNow, recordedBy, onToggleRecord)
}
