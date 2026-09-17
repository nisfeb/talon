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
import io.nisfeb.talon.orrery.OrreryAction
import io.nisfeb.talon.orrery.OrreryRepo
import io.nisfeb.talon.orrery.eventToAdd
import io.nisfeb.talon.orrery.messageToSend
import kotlinx.coroutines.launch

/**
 * One of the analyst's proposals, and what to do with it. A task or a
 * note is the ship's to hold: Done or Dismiss. A message or an event
 * is Talon's to carry out, behind this one confirm, after which the
 * ship hears done or failed with why.
 */
@Composable
fun OrreryActionDialog(
    action: OrreryAction,
    orrery: OrreryRepo,
    send: suspend (whom: String, text: String) -> Unit,
    addEvent: suspend (title: String, startMs: Long, endMs: Long) -> Boolean,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val message = remember(action) { action.messageToSend() }
    val event = remember(action) { action.eventToAdd() }
    val executable = message != null || event != null

    fun move(status: String, why: String = "") {
        busy = true
        scope.launch {
            orrery.setAction(action.id, status, why).fold(onSuccess = { onClose() }, onFailure = { note = it.message; busy = false })
        }
    }

    fun carryOut() {
        busy = true
        scope.launch {
            val failed = runCatching {
                when {
                    message != null -> send(message.whom, message.text)
                    event != null -> if (!addEvent(event.title, event.startMs, event.endMs)) error("the calendar refused the event")
                }
            }.exceptionOrNull()
            if (failed == null) move("done") else move("failed", failed.message ?: "did not go through")
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(action.title.ifBlank { action.kind }) },
        text = {
            Column {
                Text(
                    action.kind + " proposed by " + action.by.ifBlank { "the assistant" } + (action.due?.let { ", due $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text("To ${it.whom}:", style = MaterialTheme.typography.labelMedium)
                    Text(it.text, style = MaterialTheme.typography.bodyMedium)
                }
                event?.let {
                    Spacer(Modifier.height(8.dp))
                    Text("${it.title}, ${io.nisfeb.talon.orrery.isoUtc(it.startMs)} to ${io.nisfeb.talon.orrery.isoUtc(it.endMs)}", style = MaterialTheme.typography.bodyMedium)
                }
                if (action.about.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("About " + action.about.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (action.kind !in setOf("task", "note") && !executable) {
                    Spacer(Modifier.height(8.dp))
                    Text("Talon cannot carry this kind out; mark it done once you have, or dismiss it.", style = MaterialTheme.typography.bodySmall)
                }
                note?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (executable) TextButton(enabled = !busy, onClick = { carryOut() }) { Text(if (message != null) "Send it" else "Add it") }
            else TextButton(enabled = !busy, onClick = { move("done") }) { Text("Done") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = { move("dismissed") }) { Text("Dismiss", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        },
    )
}
