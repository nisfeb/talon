package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.mail.AuspexApi
import io.nisfeb.talon.mail.MailRepo
import io.nisfeb.talon.mail.parseRecipients
import io.nisfeb.talon.util.PickedImage
import io.nisfeb.talon.util.rememberAnyFilePicker
import kotlinx.coroutines.launch

/**
 * What a composer is answering, if anything.
 *
 * Compose, reply and forward are one request on the wire, differing
 * only in [prev]. A reply keeps the conversation's recipients; a
 * forward is a reply addressed to somebody new, which is exactly why
 * [travels] matters more there than anywhere else.
 */
data class MailIntent(
    val prev: String? = null,
    val to: List<String> = emptyList(),
    val subject: String = "",
    /** How many signed messages a send from here carries. Read off the
     *  thread's tree, never counted separately. */
    val travels: Int = 0,
    val forwarding: Boolean = false,
)

/**
 * Writing one message.
 *
 * The disclosure line is not decoration. A send from a thread carries
 * the whole path from the root down to the message it answers, and a
 * forward puts that path in front of somebody who has never seen any of
 * it. So the count is stated where the recipients are chosen, and it
 * comes from the same path the reader lights.
 */
@Composable
fun MailComposer(
    repo: MailRepo,
    intent: MailIntent,
    onSent: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val pickFile = rememberAnyFilePicker()

    val recipients = remember(intent) { mutableStateListOf(*intent.to.toTypedArray()) }
    var recipientDraft by remember(intent) { mutableStateOf("") }
    var subject by remember(intent) { mutableStateOf(intent.subject) }
    var body by remember(intent) { mutableStateOf("") }
    val files = remember(intent) { mutableStateListOf<PickedImage>() }
    var sending by remember(intent) { mutableStateOf(false) }
    var progress by remember(intent) { mutableStateOf<String?>(null) }
    var problem by remember(intent) { mutableStateOf<String?>(null) }

    fun commitRecipients(): List<String> {
        val (good, bad) = parseRecipients(recipientDraft)
        good.forEach { if (it !in recipients) recipients += it }
        recipientDraft = bad.joinToString(" ")
        problem = if (bad.isEmpty()) null else "Not a ship: ${bad.joinToString(", ")}"
        return recipients.toList()
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
            }
            Text(
                when {
                    intent.forwarding -> "Forward"
                    intent.prev != null -> "Reply"
                    else -> "New message"
                },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            Button(
                enabled = !sending,
                onClick = {
                    val to = commitRecipients()
                    when {
                        to.isEmpty() -> problem = "Say who this is going to."
                        body.isBlank() -> problem = "Nothing to send."
                        else -> {
                            sending = true
                            problem = null
                            scope.launch {
                                val refs = mutableListOf<io.nisfeb.talon.mail.AttachRef>()
                                var failed: String? = null
                                // One at a time, and named in the progress,
                                // because "uploading 2 of 5" is only true if
                                // there is one in flight.
                                files.forEachIndexed { i, f ->
                                    if (failed != null) return@forEachIndexed
                                    progress = "Uploading ${i + 1} of ${files.size}"
                                    val hash = runCatching { repo.uploadBlob(f.bytes) }
                                        .getOrElse { failed = "${f.displayName}: ${it.message}"; null }
                                    if (hash != null) {
                                        refs += io.nisfeb.talon.mail.AttachRef(
                                            name = f.displayName,
                                            mime = f.mimeType,
                                            hash = hash,
                                        )
                                    }
                                }
                                if (failed != null) {
                                    problem = failed
                                    progress = null
                                    sending = false
                                    return@launch
                                }
                                progress = "Sending"
                                val ok = repo.send(to, subject, body, intent.prev, refs)
                                progress = null
                                sending = false
                                if (ok) onSent() else problem = repo.error.value ?: "Send refused."
                            }
                        }
                    }
                },
            ) { Text("Send") }
        }
        HorizontalDivider()

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (intent.prev != null) {
                TravelNotice(intent.travels, intent.forwarding)
            }

            if (recipients.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    recipients.forEach { r ->
                        AssistChip(onClick = { recipients.remove(r) }, label = { Text(r) })
                    }
                }
            }
            OutlinedTextField(
                value = recipientDraft,
                onValueChange = { recipientDraft = it },
                label = { Text("To") },
                placeholder = { Text("~sampel-palnet") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { commitRecipients() }) { Text("Add recipient") }

            OutlinedTextField(
                value = subject,
                onValueChange = { subject = it },
                label = { Text("Subject") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text("Message") },
                minLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )

            if (files.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    files.forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${f.displayName} · ${sizeLabel(f.bytes.size.toLong())}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { files.remove(f) }) { Text("Remove") }
                        }
                    }
                }
            }
            TextButton(
                enabled = files.size < AuspexApi.MAX_ATTACHMENTS && !sending,
                onClick = {
                    scope.launch {
                        val picked = runCatching { pickFile() }
                            .getOrElse { problem = it.message; null } ?: return@launch
                        // Refused where it is chosen rather than after the
                        // bytes have gone up and come back rejected.
                        if (picked.bytes.size > AuspexApi.MAX_BLOB_BYTES) {
                            problem = "${picked.displayName} is over " +
                                "${AuspexApi.MAX_BLOB_BYTES / 1024} KB, which is the ship's limit."
                        } else {
                            files += picked
                            problem = null
                        }
                    }
                },
            ) { Text("Attach a file") }

            progress?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
            problem?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * What goes with this message. Said before the recipients are chosen,
 * not after, because on a forward the recipients are the people who
 * have never seen any of it.
 */
@Composable
private fun TravelNotice(travels: Int, forwarding: Boolean) {
    val what = when (travels) {
        0, 1 -> "This message alone travels"
        else -> "$travels signed messages travel"
    }
    val who = if (forwarding) " to people who have not seen them." else " with this reply."
    Text(
        what + who,
        style = MaterialTheme.typography.bodySmall,
        color = if (forwarding) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
