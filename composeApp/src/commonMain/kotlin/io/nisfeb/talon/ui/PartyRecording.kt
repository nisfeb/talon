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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

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

    var recordingNow by remember(partyRoomHere) { mutableStateOf(false) }
    var recordConsented by remember { mutableStateOf(false) }
    var showRecordConsent by remember { mutableStateOf(false) }
    var recordedResult by remember(partyRoomHere) { mutableStateOf<RecordedCall?>(null) }

    // Heartbeat our recording to the host while active; say we stopped
    // on the way out.
    LaunchedEffect(recordingNow, partyRoomHere) {
        if (!recordingNow) return@LaunchedEffect
        val (h, n) = partyRoomHere ?: return@LaunchedEffect
        val cc = callController ?: return@LaunchedEffect
        try {
            while (true) {
                cc.startRecording(h, n)
                delay(30_000)
            }
        } finally {
            withContext(NonCancellable) { cc.stopRecording(h, n) }
        }
    }

    val canRecord = isCallRecordingSupported &&
        onThisLine && partyLine != null && ourShip.isNotEmpty()
    val onToggleRecord: (() -> Unit)? = if (canRecord) {
        {
            val line = partyLine!!
            if (recordingNow) {
                recordingNow = false
                recordedResult = line.stopRecording()
            } else if (!recordConsented) {
                showRecordConsent = true
            } else {
                recordingNow = true
                line.startRecording(ourShip)
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
            recordingNow = true
            partyLine?.startRecording(ourShip)
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
            onClose = { recordedResult = null },
        )
    }

    return PartyRecordingControls(recordingNow, recordedBy, onToggleRecord)
}
