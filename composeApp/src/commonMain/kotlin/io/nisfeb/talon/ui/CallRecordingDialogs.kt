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

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
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
                        }.onSuccess { message = "Published to $it" }
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
                            val wav = CallRecordingPublisher.fullRecordingWav(rec)
                            val saved = if (wav != null) {
                                saveWavFile(wav, "party-line-${io.nisfeb.talon.util.nowMs()}")
                            } else {
                                null
                            }
                            message = if (saved != null) {
                                "Saved audio to $saved"
                            } else {
                                "Couldn't save audio on this platform."
                            }
                            busy = false
                        }
                    },
                ) { Text("Save full recording") }
                TextButton(enabled = !busy, onClick = onClose) { Text("Done") }
            }
        },
    )
}
