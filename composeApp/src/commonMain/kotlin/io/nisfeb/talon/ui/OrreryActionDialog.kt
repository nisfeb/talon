package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import kotlinx.datetime.toLocalDateTime

/**
 * One of the analyst's proposals, and what to do with it. A task or a
 * note is the ship's to hold: Done or Dismiss. A message is Talon's to
 * send, behind this one confirm, after which the ship hears done or
 * failed with why. An event is approved here and put on the calendar
 * by the mirror, the one place that makes it, linked back to the action.
 */
@Composable
fun OrreryActionDialog(
    action: OrreryAction,
    orrery: OrreryRepo,
    send: suspend (whom: String, text: String) -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    // Why not, in the owner's words, only if they give it: it teaches
    // the generator what they do not want.
    var reason by remember { mutableStateOf("") }
    val message = remember(action) { action.messageToSend() }
    val event = remember(action) { action.eventToAdd() }
    val executable = message != null

    // Taken at once: the dialog closes and the ship is told behind it.
    fun move(status: String, why: String = "") {
        orrery.answer(action.id, status, why)
        onClose()
    }

    // The ship allows a proposal to be approved or dismissed and nothing
    // else, and an approved action to be done, failed or dismissed. The
    // buttons follow that, instead of offering a Done the ship refuses.
    val proposed = action.status == "proposed"

    fun carryOut() {
        busy = true
        scope.launch {
            // A proposal is approved on the way: doing it is saying yes.
            if (proposed) {
                orrery.setAction(action.id, "approved").onFailure { note = it.message; busy = false; return@launch }
            }
            val failed = runCatching { message?.let { send(it.whom, it.text) } }.exceptionOrNull()
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
                    Text(whenLine(it), style = MaterialTheme.typography.bodyMedium)
                }
                if (action.about.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("About " + action.about.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        proposed && executable -> "Waiting for you. Sending it approves it too."
                        proposed && action.kind == "task" -> "Waiting for you. Approved, it goes on your calendar's task list."
                        proposed && event != null -> "Waiting for you. Approved, it goes on your calendar."
                        event != null -> "Approved. It goes on your calendar on the next pass."
                        proposed -> "Waiting for you."
                        executable -> "Approved, not yet done."
                        action.kind == "task" -> "Approved, and on your task list. Mark it done here or tick it there."
                        else -> "Approved. Talon cannot carry this kind out; mark it done once you have."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (proposed || action.status == "approved") {
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text("Why not? (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                        DISMISS_REASONS.forEach { r ->
                            androidx.compose.material3.AssistChip(onClick = { reason = r }, label = { Text(r) })
                        }
                    }
                    Text(
                        "A reason goes to the generator with the dismissal, so it stops proposing things like this one.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                note?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
            ) {
                TextButton(enabled = !busy, onClick = onClose) { Text("Close", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton(enabled = !busy, onClick = { move("dismissed", reason) }) {
                    Text("Dismiss", color = MaterialTheme.colorScheme.error)
                }
                when {
                    // Doing it, where Talon can: that approves a proposal too.
                    executable -> {
                        if (proposed) TextButton(enabled = !busy, onClick = { move("approved") }) { Text("Approve only") }
                        androidx.compose.material3.Button(enabled = !busy, onClick = { carryOut() }) {
                            Text("Send it")
                        }
                    }
                    proposed -> androidx.compose.material3.Button(enabled = !busy, onClick = { move("approved") }) { Text("Approve") }
                    else -> androidx.compose.material3.Button(enabled = !busy, onClick = { move("done") }) { Text("Mark done") }
                }
            }
        },
    )
}

/** The reasons the client guide gives as examples, one tap each; the owner's own words go in the field. */
val DISMISS_REASONS = listOf("just the event", "I always do this")

/** The event as the owner reads it: local times, and the place where there is one. */
private fun whenLine(e: io.nisfeb.talon.orrery.EventToAdd): String {
    val zone = kotlinx.datetime.TimeZone.currentSystemDefault()
    fun at(ms: Long) = kotlinx.datetime.Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)
    val start = at(e.startMs)
    val end = e.endMs?.let(::at)
    val span = "${start.date} ${start.time}" + (end?.let { if (it.date == start.date) " to ${it.time}" else " to ${it.date} ${it.time}" } ?: "")
    return "${e.title}, $span" + (e.location?.takeIf { it.isNotBlank() }?.let { ", $it" } ?: "")
}
