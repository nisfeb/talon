package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.mail.Draft
import io.nisfeb.talon.mail.MailAvailability
import io.nisfeb.talon.mail.MailRepo
import io.nisfeb.talon.mail.newDraftId
import io.nisfeb.talon.ui.screens.Quiet
import io.nisfeb.talon.util.LogBuffer
import kotlinx.coroutines.launch

/** Where Settings, About, Report a bug sends: sneagan's ship, which runs Auspex (2026-10-11). */
const val BUG_REPORT_TO = "~ricsul-bilwyt"

/** The logs' file name on the mail. */
const val BUG_REPORT_LOG_FILE = "talon-log.txt"

/** One bug report as a mail: its subject and body, and the logs as a file, or null without them. */
class BugReport(val subject: String, val body: String, val logs: ByteArray?)

/**
 * A report from what the user wrote, what the app is ([about], as label
 * and value), and the recent log lines when they chose to send them.
 */
fun bugReport(description: String, about: List<Pair<String, String>>, logs: List<String>?): BugReport {
    val text = description.trim()
    val first = text.lineSequence().first().let { if (it.length > 60) it.take(60).trimEnd() + "…" else it }
    val body = buildString {
        append(text).append("\n\n")
        about.forEach { (k, v) -> append(k).append(": ").append(v).append('\n') }
        append(if (logs != null) "Logs: ${logs.size} lines, in $BUG_REPORT_LOG_FILE" else "Logs: not sent")
    }
    return BugReport("Talon bug: $first", body, logs?.joinToString("\n", postfix = "\n")?.encodeToByteArray())
}

/**
 * Settings, About: say what went wrong, and it goes to Talon's developer
 * as Auspex mail from the user's own ship, with this run's recent log
 * lines as a file if they choose. Without Auspex on the ship it cannot be
 * sent from here, so the report is offered to copy instead.
 *
 * The send runs on the mail repo's scope ([MailRepo.sendMessage]), so
 * closing this or leaving Settings does not stop it; one that fails is
 * in Mail's unsent list and Drafts.
 */
@Composable
fun ReportBugDialog(mail: MailRepo?, about: List<Pair<String, String>>, onDismiss: () -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var text by remember { mutableStateOf("") }
    var withLogs by remember { mutableStateOf(true) }
    var showLogs by remember { mutableStateOf(false) }
    val logs = remember { LogBuffer.recent() }
    val availability = mail?.availability?.collectAsState()?.value
    var stage by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var sent by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val canSend = mail != null && availability == MailAvailability.PRESENT
    fun report() = bugReport(text, about, logs.takeIf { withLogs })

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (sent) "Report sent" else "Report a bug") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sent) {
                    Text("It went to $BUG_REPORT_TO as mail from your ship. Thank you.")
                    return@Column
                }
                Text("Say what happened and what you expected. It goes to Talon's developer at $BUG_REPORT_TO as Auspex mail from your ship.")
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; problem = null; copied = false },
                    label = { Text("What went wrong?") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = withLogs, onCheckedChange = { withLogs = it })
                    Text("Include recent logs (${logs.size} lines)", modifier = Modifier.weight(1f))
                    TextButton(onClick = { showLogs = !showLogs }) { Text(if (showLogs) "Hide" else "Show") }
                }
                Quiet("Logs can name ships and chats you used. They go only if this is ticked.")
                if (showLogs) {
                    SelectionContainer {
                        Text(
                            logs.joinToString("\n").ifEmpty { "Nothing logged yet." },
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                        )
                    }
                }
                when {
                    canSend -> Unit
                    mail == null || availability == MailAvailability.UNKNOWN -> Quiet("Checking for Auspex mail on your ship…")
                    else -> Quiet("Sending needs Auspex mail on your ship, which it does not have yet. Copy the report and post it at github.com/nisfeb/talon/issues instead.")
                }
                stage?.let { Quiet("$it…") }
                problem?.let { Quiet("Not sent: $it", error = true) }
                if (copied) Quiet("Copied.")
            }
        },
        confirmButton = {
            when {
                sent -> TextButton(onClick = onDismiss) { Text("Close") }
                canSend -> Button(enabled = text.isNotBlank() && stage == null, onClick = {
                    val r = report()
                    problem = null
                    val job = mail!!.sendMessage(
                        Draft(id = newDraftId(), to = listOf(BUG_REPORT_TO), subject = r.subject, body = r.body),
                        listOfNotNull(r.logs?.let { MailRepo.Outgoing(it, BUG_REPORT_LOG_FILE, "text/plain") }),
                    ) { stage = it }
                    scope.launch {
                        val why = job.await()
                        if (why == null) sent = true else problem = why
                    }
                }) { Text("Send") }
                else -> TextButton(enabled = text.isNotBlank(), onClick = {
                    val r = report()
                    val whole = r.subject + "\n\n" + r.body + (r.logs?.let { "\n\n" + it.decodeToString() } ?: "")
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(whole))
                    copied = true
                }) { Text("Copy report") }
            }
        },
        dismissButton = { if (!sent) TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
