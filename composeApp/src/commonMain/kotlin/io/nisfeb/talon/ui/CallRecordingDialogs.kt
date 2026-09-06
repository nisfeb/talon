package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.ai.CallRecordingPublisher
import io.nisfeb.talon.call.RecordedCall
import io.nisfeb.talon.call.saveWavFile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-time opt-in before recording a party line. Recording captures
 * everyone's audio and sends it off-ship to transcribe, and the whole
 * room is told — so it is a deliberate choice, not a silent toggle.
 */
@Composable
fun RecordConsentDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!show) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record this call?") },
        text = {
            Text(
                "Recording captures everyone's audio. Talon sends it to " +
                    "your transcription provider to make a transcript, and " +
                    "everyone on the line sees a recording badge while it's on.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Start recording") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * After a recording stops: publish the transcript to Lattice and/or
 * keep the full audio. Both run best-effort and report inline.
 */
@Composable
fun RecordingResultDialog(
    rec: RecordedCall,
    http: HttpClient,
    sttConfig: AiSettings.Config,
    shipUrl: String?,
    cookie: String?,
    ourShip: String,
    title: String,
    nameFor: (String) -> String,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val stt = remember(sttConfig) { CallRecordingPublisher.sttFrom(sttConfig) }
    val canPublish = !rec.isEmpty && stt != null && shipUrl != null && cookie != null
    val whenLabel = remember { io.nisfeb.talon.util.formatMonthDayTime(io.nisfeb.talon.util.nowMs()) }

    // Track whether anything has been done with the audio, so "Done"
    // can warn before it becomes the thing that threw it away.
    var kept by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    AlertDialog(
        // Deliberately inert: this dialog holds the ONLY copy of the
        // recording — it is never written anywhere until one of these
        // buttons runs — so an accidental tap outside used to destroy
        // it with no undo and no warning.
        onDismissRequest = {},
        title = { Text("Recording finished") },
        text = {
            Column {
                Text(
                    if (rec.isEmpty) {
                        "No audio was captured."
                    } else {
                        val n = rec.clips.size
                        "Captured ${if (n == 1) "1 speaker" else "$n speakers"}."
                    },
                )
                if (!rec.isEmpty && stt == null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "To publish a transcript, set an OpenAI-compatible " +
                            "transcription key in AI settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (canPublish) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Publishing sends each speaker's audio to your " +
                            "transcription provider and puts the transcript on " +
                            "your ship, where anyone who can read your Lattice " +
                            "namespace can see it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (confirmDiscard) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This is the only copy. Tap Discard again to delete it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && canPublish,
                onClick = {
                    busy = true
                    message = null
                    scope.launch {
                        runCatching {
                            CallRecordingPublisher.publishTranscript(
                                http, stt!!, shipUrl!!, ourShip, cookie!!,
                                title, whenLabel, rec, nameFor,
                            )
                        }.onSuccess { message = "Published to $it"; kept = true }
                            .onFailure { message = "Publish failed: ${it.message ?: "error"}" }
                        busy = false
                    }
                },
            ) { Text("Publish transcript") }
        },
        dismissButton = {
            Column {
                TextButton(
                    enabled = !busy && !rec.isEmpty,
                    onClick = {
                        busy = true
                        message = null
                        scope.launch {
                            // rememberCoroutineScope launches on Main, and
                            // mixing + WAV-encoding a long recording is
                            // hundreds of megabytes of array work — it froze
                            // the UI and tripped the ANR watchdog.
                            val wav = withContext(io.nisfeb.talon.util.ioDispatcher) {
                                CallRecordingPublisher.fullRecordingWav(rec)
                            }
                            val saved = if (wav != null) {
                                withContext(io.nisfeb.talon.util.ioDispatcher) {
                                    saveWavFile(wav, "party-line-${io.nisfeb.talon.util.nowMs()}")
                                }
                            } else {
                                null
                            }
                            if (saved != null) kept = true
                            message = if (saved != null) {
                                "Saved audio to $saved"
                            } else {
                                "Couldn't save audio on this platform."
                            }
                            busy = false
                        }
                    },
                ) { Text("Save full recording") }
                TextButton(
                    enabled = !busy,
                    onClick = {
                        // Nothing has been saved or published, so this
                        // button IS the delete. Ask once.
                        if (kept || rec.isEmpty || confirmDiscard) onClose()
                        else confirmDiscard = true
                    },
                ) { Text(if (kept || rec.isEmpty) "Done" else "Discard") }
            }
        },
    )
}
