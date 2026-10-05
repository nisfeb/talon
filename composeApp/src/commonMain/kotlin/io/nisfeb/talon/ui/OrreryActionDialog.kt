package io.nisfeb.talon.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import io.nisfeb.talon.ui.TextButton
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
import io.nisfeb.talon.orrery.shipChange
import io.nisfeb.talon.urbit.asText
import kotlinx.datetime.toLocalDateTime

/**
 * One of the analyst's proposals, and what to do with it: approve it,
 * reject it with the owner's reason, leave it for later, change it, add
 * a note for Orrery's model, or mark it done. Carrying it out is the ship's, bar a chat message,
 * which Talon's executor claims and sends.
 */
@Composable
fun OrreryActionDialog(
    action: OrreryAction,
    orrery: OrreryRepo,
    onClose: () -> Unit,
    /** The owner's clock, from the 12 or 24 hour setting. */
    twentyFourHour: Boolean = false,
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
    var saying by remember(action.id) { mutableStateOf(Saying.NOTHING) }
    // The owner's words about it for the ship's model (orrery 60): "she
    // moved to Lisbon", "never propose these". What it files is proposed.
    var telling by remember(action.id) { mutableStateOf("") }
    var shown by remember(action.id) { mutableStateOf(action) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val action = shown
    val message = remember(action) { action.messageToSend() }
    val event = remember(action) { action.eventToAdd() }
    val how = message?.let { if (it.via == "chat") "as a DM" else "by ${it.via}" }
    val change = remember(action) { action.shipChange() }

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
            // Scrolls: a long message pushed the buttons off a phone's screen.
            // The buttons stay outside it, always in reach.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    // "The analyst", as the notification says: the
                    // assistant is the one you talk to, and did not.
                    action.kind + " proposed by " + action.by.ifBlank { "the analyst" } +
                        (action.due?.let { io.nisfeb.talon.orrery.OrreryText.dueText(it, kotlinx.datetime.TimeZone.currentSystemDefault(), twentyFourHour) }?.let { ", due $it" } ?: ""),
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
                change?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                action.payload["why"].asText()?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(8.dp))
                    Text("Why: $it", style = MaterialTheme.typography.bodySmall)
                }
                if (action.about.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("About " + action.about.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                // What each button does, in its own words: "Waiting for you.
                // Approved, the ship sends it" read as if it had been sent.
                Text(
                    when {
                        proposed && action.kind == "task" -> "Approve adds it to your task list."
                        proposed && event != null -> "Approve puts it on your calendar at that time. You can move it there after."
                        proposed && how != null -> "Approve sends this message $how."
                        proposed && change != null -> "Approve lets the ship make this change."
                        proposed -> "Approve accepts it. Talon cannot carry this kind out, so mark it done once you have."
                        action.status == "failed" -> "It did not go through. " + action.note.ifBlank { "The ship gave no reason." }
                        // The ship carries it out on its own executor, the
                        // moment the owner approves.
                        event != null -> "Approved. The ship puts it on your calendar."
                        how != null -> "Approved. The ship sends it $how."
                        change != null -> "Approved. The ship makes the change itself."
                        action.kind == "task" -> "Approved, and on your task list. Tick it there, or mark it done here."
                        else -> "Approved. Talon cannot carry this kind out. Mark it done once you have."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (proposed && saying == Saying.NOTHING) {
                    Text(
                        "Later keeps it waiting on the Actions page.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // The other things that can be done with it, a word each
                // until asked for. Each opens its field, and its button
                // takes the place of Approve below, with Back beside it.
                if ((proposed || action.status == "approved") && saying == Saying.NOTHING) {
                    Spacer(Modifier.height(12.dp))
                    // Wrapping, not a Row: side by side they did not fit
                    // across a phone's dialog.
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Or:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                        if (proposed && action.kind in REFINABLE) {
                            TextButton(onClick = { saying = Saying.REFINE }) {
                                io.nisfeb.talon.ui.FitText("Change it")
                            }
                        }
                        TextButton(onClick = { saying = Saying.TELL }) {
                            io.nisfeb.talon.ui.FitText("Note for Orrery")
                        }
                    }
                }
                if (saying == Saying.TELL) {
                    androidx.compose.material3.OutlinedTextField(
                        value = telling,
                        onValueChange = { telling = it },
                        label = { Text("Note for Orrery") },
                        placeholder = { Text("she moved to Lisbon") },
                        enabled = !refining,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Orrery's model reads it with this proposal. Anything it proposes from it waits on the Actions page for you.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (proposed && action.kind in REFINABLE && saying == Saying.REFINE) {
                    androidx.compose.material3.OutlinedTextField(
                        value = refinement,
                        onValueChange = { refinement = it },
                        label = { Text("What should change?") },
                        placeholder = { Text("include susan egan in this") },
                        enabled = !refining,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "The ship rewrites the proposal and shows it here, still waiting for you.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (saying == Saying.REJECT) {
                    Spacer(Modifier.height(12.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text("Reason (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DISMISS_REASONS.forEach { r ->
                            androidx.compose.material3.AssistChip(onClick = { reason = r }, label = { Text(r) })
                        }
                    }
                    Text(
                        "Orrery learns from a reason and proposes fewer like this.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // What the ship said about a change outlives the field.
                refined?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        // Every button that does something sits here, outside the scroll,
        // so none is lost below a long message: the way out on the left,
        // no in the middle, yes on the right and filled. "Close" beside a
        // "Dismiss" read as the same thing, so a proposal's way out is
        // "Later" and saying no is "Reject". Opening a field swaps Approve
        // for that field's own button, and the way out for Back.
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val noWord = if (proposed) "Reject" else "Remove"
                when (saying) {
                    Saying.REJECT -> io.nisfeb.talon.ui.Button(
                        enabled = !refining,
                        onClick = { move("dismissed", reason) },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) { Text(noWord) }
                    // Taken at once, like Approve: the model can take two
                    // minutes, and the dialog waited on it. What it says
                    // comes up on the Actions page.
                    Saying.TELL -> io.nisfeb.talon.ui.Button(
                        enabled = !refining && telling.isNotBlank(),
                        onClick = { orrery.tell(telling, action.id); onClose() },
                    ) { Text("Send note") }
                    Saying.REFINE -> io.nisfeb.talon.ui.Button(
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
                                        answer.action?.let { shown = it; refinement = ""; saying = Saying.NOTHING }
                                        refined = when {
                                            answer.action == null -> answer.note.ifBlank { "The ship would not take that." }
                                            answer.extras.isEmpty() -> answer.note.ifBlank { "Revised." }
                                            // Filed the way any proposal is: under the
                                            // owner's auto list a task can land approved,
                                            // so this does not say which it did.
                                            else -> (answer.note.ifBlank { "Revised." }) +
                                                " And filed beside it: " + answer.extras.joinToString { it.title }
                                        }
                                    },
                                    onFailure = { refined = io.nisfeb.talon.util.problemOf("Couldn't ask for the change", it).line },
                                )
                                refining = false
                            }
                        },
                    ) { Text(if (refining) "Asking…" else "Ask for the change") }
                    Saying.NOTHING -> when {
                        // Held while a change is being asked for: an approval
                        // that lands mid-refinement is refused by the ship and
                        // files nothing, so the tap would be a tap that did nothing.
                        proposed -> {
                            io.nisfeb.talon.ui.DestructiveTextButton(onClick = { saying = Saying.REJECT }) { Text(noWord) }
                            io.nisfeb.talon.ui.Button(enabled = !refining, onClick = { move("approved") }) {
                                Text(if (message != null) "Approve and send" else "Approve")
                            }
                        }
                        action.status == "approved" -> {
                            io.nisfeb.talon.ui.DestructiveTextButton(onClick = { saying = Saying.REJECT }) { Text(noWord) }
                            // What the ship carries out reports itself; marking
                            // it done here would skip the doing.
                            if (event == null && message == null && change == null) {
                                io.nisfeb.talon.ui.Button(onClick = { move("done") }) { Text("Mark done") }
                            }
                        }
                        // Failed, done or dismissed: nothing leaves those.
                        else -> Unit
                    }
                }
            }
        },
        dismissButton = {
            when {
                saying != Saying.NOTHING -> TextButton(onClick = { saying = Saying.NOTHING }) { Text("Back") }
                proposed -> TextButton(onClick = onClose) { Text("Later") }
                else -> TextButton(onClick = onClose) { Text("Close") }
            }
        },
    )
}

/**
 * The kinds the ship will rewrite. A merge or a home action has no
 * payload shape a rewrite can be held to, so asking about one answers
 * 409; the input is not offered for them (rule 17).
 */
private val REFINABLE = setOf("task", "calendar", "message")

/** Which of the things that are not approving the owner is doing. */
private enum class Saying { NOTHING, REFINE, TELL, REJECT }

/** The reasons the client guide gives as examples, one tap each; the owner's own words go in the field. */
val DISMISS_REASONS = listOf("just the event", "I always do this")

/** The event as the owner reads it: local times, and the place where there is one. */
private fun whenLine(e: io.nisfeb.talon.orrery.EventToAdd): String {
    val zone = kotlinx.datetime.TimeZone.currentSystemDefault()
    fun at(ms: Long) = kotlin.time.Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)
    val start = at(e.startMs)
    val end = e.endMs?.let(::at)
    val span = "${start.date} ${start.time}" + (end?.let { if (it.date == start.date) " to ${it.time}" else " to ${it.date} ${it.time}" } ?: "")
    return "${e.title}, $span" + (e.location?.takeIf { it.isNotBlank() }?.let { ", $it" } ?: "")
}
