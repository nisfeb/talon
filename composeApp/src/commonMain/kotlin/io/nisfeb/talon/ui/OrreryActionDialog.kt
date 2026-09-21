package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.orrery.OrreryAction
import io.nisfeb.talon.orrery.OrreryRepo
import io.nisfeb.talon.orrery.eventToAdd
import io.nisfeb.talon.orrery.messageToSend
import kotlinx.datetime.toLocalDateTime

/**
 * One of the analyst's proposals, and what to do with it: approve,
 * dismiss with the owner's reason, say what it should have said, or
 * mark it done. Carrying it out is the ship's, bar a chat message,
 * which Talon's executor claims and sends.
 */
@Composable
fun OrreryActionDialog(
    action: OrreryAction,
    orrery: OrreryRepo,
    onClose: () -> Unit,
) {
    // Why not, in the owner's words, only if they give it: it teaches
    // the generator what they do not want.
    var reason by remember { mutableStateOf("") }
    // What the owner would have it say instead, rule 17. The ship reads
    // it against the action and answers with the action revised; the
    // window redraws from that answer, never from what was typed.
    var refinement by remember(action.id) { mutableStateOf("") }
    var refining by remember(action.id) { mutableStateOf(false) }
    var refined by remember(action.id) { mutableStateOf<String?>(null) }
    var shown by remember(action.id) { mutableStateOf(action) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val action = shown
    val message = remember(action) { action.messageToSend() }
    val event = remember(action) { action.eventToAdd() }
    val ours = message?.via in io.nisfeb.talon.orrery.TALON_CHANNELS

    // Taken at once: the dialog closes and the ship is told behind it.
    fun move(status: String, why: String = "") {
        orrery.answer(action.id, status, why)
        onClose()
    }

    // The ship allows a proposal to be approved or dismissed and nothing
    // else, and an approved action to be done, failed or dismissed. The
    // buttons follow that, instead of offering a Done the ship refuses.
    val proposed = action.status == "proposed"

    AlertDialog(
        onDismissRequest = onClose,
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
                    Text("To ${it.to}, by ${if (it.via == "chat") "DM" else it.via}:", style = MaterialTheme.typography.labelMedium)
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
                        proposed && action.kind == "task" -> "Waiting for you. Approved, the ship puts it on your calendar's task list."
                        proposed && event != null -> "Waiting for you. Approved, the ship puts it on your calendar at that time; move it there if it is wrong."
                        proposed && ours -> "Waiting for you. Approved, Talon sends it as a DM."
                        proposed && message != null -> "Waiting for you. Approved, the ship sends it by ${message.via}."
                        proposed -> "Waiting for you."
                        // The ship carries out everything but a DM now, on
                        // its own executor, the moment the owner approves.
                        event != null -> "Approved. The ship puts it on your calendar."
                        ours -> "Approved. Talon sends it as a DM on the next pass."
                        message != null -> "Approved. The ship sends it by ${message.via}."
                        action.kind == "task" -> "Approved, and on your task list. Tick it there, or mark it done here."
                        else -> "Approved. Talon cannot carry this kind out; mark it done once you have."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (proposed) {
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = refinement,
                        onValueChange = { refinement = it },
                        label = { Text("Say what it should be") },
                        placeholder = { Text("include susan egan in this") },
                        enabled = !refining,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = !refining && refinement.isNotBlank(),
                            onClick = {
                                refining = true
                                refined = null
                                val said = refinement
                                scope.launch {
                                    orrery.refine(action.id, said).fold(
                                        onSuccess = { answer ->
                                            // The ship's word for it, not the owner's:
                                            // it may have resolved a name loosely
                                            // spelled, kept a time it could not move,
                                            // or refused.
                                            answer.action?.let { shown = it; refinement = "" }
                                            refined = when {
                                                answer.action == null -> answer.note.ifBlank { "The ship would not take that." }
                                                answer.extras.isEmpty() -> answer.note.ifBlank { "Revised." }
                                                else -> (answer.note.ifBlank { "Revised." }) +
                                                    " And proposed beside it: " + answer.extras.joinToString { it.title }
                                            }
                                        },
                                        onFailure = { refined = it.message ?: "The ship did not answer." },
                                    )
                                    refining = false
                                }
                            },
                        ) { Text(if (refining) "Asking" else "Refine") }
                        refined?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
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
                    TextButton(
                        onClick = { move("dismissed", reason) },
                        modifier = Modifier.align(Alignment.End),
                    ) { Text("Dismiss", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        // The app's own order: the way out on the left, the one thing
        // this window is for on the right, filled, and no third button
        // between them. Saying no lives in the body, beside the reason
        // it sends, which is where it was being explained anyway.
        confirmButton = {
            when {
                proposed -> androidx.compose.material3.Button(onClick = { move("approved") }) {
                    Text(if (ours) "Approve and send" else "Approve")
                }
                // What is carried out reports itself, by the ship or by
                // Talon; marking it done here would skip the doing.
                event != null || ours || message != null -> Unit
                else -> androidx.compose.material3.Button(onClick = { move("done") }) { Text("Mark done") }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
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
