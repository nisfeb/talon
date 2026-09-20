package io.nisfeb.talon.ui.screens

import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
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
    /** The draft this is editing, when it came from one. Kept so a save
     *  overwrites rather than piling up a new draft per keystroke. */
    val draftId: String? = null,
    val body: String = "",
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
    /**
     * Put a file on the ship's own storage and answer with its address:
     * what an attachment too big for a mail is offered instead. Null
     * where the host has no storage to offer, and then the offer is not
     * made. Takes the bytes, the content type and the name.
     */
    upload: (suspend (ByteArray, String, String) -> String)? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val pickFile = rememberAnyFilePicker()
    val lists by repo.lists.collectAsState()
    androidx.compose.runtime.LaunchedEffect(repo) { repo.refreshLists() }

    val recipients = remember(intent) { mutableStateListOf(*intent.to.toTypedArray()) }
    // A fresh mail starts at To; a reply already has one, so it starts
    // at the message.
    val toFocus = remember { FocusRequester() }
    val bodyFocus = remember { FocusRequester() }
    LaunchedEffect(intent) {
        runCatching { (if (intent.to.isEmpty()) toFocus else bodyFocus).requestFocus() }
    }
    var recipientDraft by remember(intent) { mutableStateOf("") }
    var subject by remember(intent) { mutableStateOf(intent.subject) }
    var body by remember(intent) { mutableStateOf(intent.body) }
    // Minted once per composer, and reused, so saving twice overwrites.
    val draftId = remember(intent) { intent.draftId ?: io.nisfeb.talon.mail.newDraftId() }
    val files = remember(intent) { mutableStateListOf<PickedImage>() }
    var sending by remember(intent) { mutableStateOf(false) }
    var progress by remember(intent) { mutableStateOf<String?>(null) }
    var problem by remember(intent) { mutableStateOf<String?>(null) }
    // Set once what is here has been accounted for, sent or saved, so
    // that the composer going away afterwards does not file a message
    // that has just gone out as a draft.
    var filed by remember(intent) { mutableStateOf(false) }
    // A file too big to attach, waiting on the owner's answer about
    // sending it as a link instead.
    var oversize by remember(intent) { mutableStateOf<PickedImage?>(null) }

    /** What is in the composer now, as a draft. */
    fun asDraft() = io.nisfeb.talon.mail.Draft(
        id = draftId,
        to = recipients.toList(),
        subject = subject,
        body = body,
        prev = intent.prev,
    )

    // Leaving by any route keeps what was written. The back button
    // saves and closes, but a section switch (mail to chat, from the
    // drawer or the rail) takes this composable out of composition
    // outright, and on Android the screen holding the intent goes with
    // it: what was typed was simply gone, which is the one failure a
    // composer must not have. onDispose cannot wait for a save and this
    // composable's scope is cancelled with it, so the repo's does it.
    DisposableEffect(intent) {
        onDispose {
            if (!filed && (body.isNotBlank() || subject.isNotBlank() || recipients.isNotEmpty())) {
                repo.keepDraft(asDraft())
            }
        }
    }

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
            IconButton(
                onClick = {
                    // Leaving with something written keeps it. Losing a
                    // half-finished message to a back gesture is the one
                    // failure a composer must not have.
                    if (body.isNotBlank() || subject.isNotBlank() || recipients.isNotEmpty()) {
                        scope.launch {
                            repo.saveDraft(asDraft())
                            filed = true // saved here; onDispose need not save it again
                            onCancel()
                        }
                    } else if (intent.draftId != null) {
                        // Opened from a draft and emptied out: keeping
                        // the husk would say there is still something
                        // to send.
                        scope.launch {
                            repo.deleteDraft(intent.draftId)
                            onCancel()
                        }
                    } else {
                        onCancel()
                    }
                },
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
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
                        // commitRecipients leaves what it could not
                        // parse in the draft and says so in `problem`.
                        // Sending anyway dropped those people silently.
                        recipientDraft.isNotBlank() -> Unit
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
                                // A draft goes out AS a draft: save the
                                // edits, then sendDraft, which the ship
                                // deletes only when the send landed. A
                                // send() + deleteDraft() drops it on an
                                // accepted poke — and an accepted poke is
                                // not an applied one. With files attached
                                // there is no draft route that carries
                                // them, so that send goes direct.
                                val ok = if (intent.draftId != null && refs.isEmpty()) {
                                    // The save has to land before the
                                    // ship is asked to send by id: a
                                    // transient failure here would
                                    // otherwise send the STALE stored
                                    // draft to the stale recipients,
                                    // report success, and delete it.
                                    if (!repo.saveDraft(
                                            io.nisfeb.talon.mail.Draft(
                                                id = draftId,
                                                to = to,
                                                subject = subject,
                                                body = body,
                                                prev = intent.prev,
                                            ),
                                        )
                                    ) {
                                        problem = repo.error.value ?: "The edits did not reach the ship; nothing was sent."
                                        progress = null
                                        sending = false
                                        return@launch
                                    }
                                    repo.sendDraft(draftId)
                                } else {
                                    repo.send(to, subject, body, intent.prev, refs)
                                }
                                progress = null
                                sending = false
                                if (ok) {
                                    filed = true
                                    // The draft, if this was one, is done
                                    // — unless the ship already dropped
                                    // it for a landed sendDraft.
                                    if (intent.draftId != null && refs.isNotEmpty()) repo.deleteDraft(intent.draftId)
                                    onSent()
                                } else {
                                    problem = repo.error.value ?: "Send refused."
                                }
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
                modifier = Modifier.fillMaxWidth().focusRequester(toFocus),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { commitRecipients() }) { Text("Add recipient") }
                // A list is a name for a set of ships, and the name never
                // travels: it expands here, so everything downstream of
                // this composer only ever sees ships.
                lists.forEach { l ->
                    TextButton(
                        onClick = {
                            l.members.forEach { if (it !in recipients) recipients += it }
                            problem = null
                        },
                    ) { Text("+ ${l.name}") }
                }
            }

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
                modifier = Modifier.fillMaxWidth().focusRequester(bodyFocus),
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
                            // Too big to travel in the message. The ship's
                            // own storage will hold it, and the message
                            // carries its address instead: offered, not
                            // done, because that address is readable by
                            // anyone who has it.
                            if (upload != null) {
                                oversize = picked
                                problem = null
                            } else {
                                problem = "${picked.displayName} is over " +
                                    "${AuspexApi.MAX_BLOB_BYTES / 1024} KB, which is the ship's limit."
                            }
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

    oversize?.let { big ->
        val kb = big.bytes.size / 1024
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { oversize = null },
            title = { Text("Too big to attach") },
            text = {
                Text(
                    "${big.displayName} is ${if (kb >= 1024) "${kb / 1024} MB" else "$kb KB"}, and a mail " +
                        "carries ${AuspexApi.MAX_BLOB_BYTES / 1024} KB at most. Your ship can hold it instead, " +
                        "and the message carries a link to it. Anyone who has the link can open it, " +
                        "including people the message was not sent to.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    enabled = !sending,
                    onClick = {
                        val put = upload ?: return@Button
                        oversize = null
                        sending = true
                        progress = "Storing ${big.displayName}"
                        scope.launch {
                            runCatching { put(big.bytes, big.mimeType, storedName(big.displayName, big.mimeType)) }
                                .onSuccess { url ->
                                    // On its own line at the end: the
                                    // thread pane finds an image there and
                                    // shows it where it stands.
                                    body = body.trimEnd() + (if (body.isBlank()) "" else "\n\n") + url
                                    problem = null
                                }
                                .onFailure {
                                    problem = "${big.displayName} was not stored: ${it.message ?: "no reason given"}. " +
                                        "A ship stores files once storage is set up in Landscape."
                                }
                            progress = null
                            sending = false
                        }
                    },
                ) { Text("Store it and link") }
            },
            dismissButton = {
                TextButton(onClick = { oversize = null }) { Text("Cancel") }
            },
        )
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

/**
 * The name a file is stored under. A picker can answer with a name
 * that has no extension at all (a content:// id on Android), and an
 * image whose address does not end in one is not shown where it
 * stands, only linked. The type the picker reported gives it one.
 * Only the types that are rendered get this: naming anything else
 * would claim something about bytes nobody has looked at.
 */
internal fun storedName(displayName: String, mime: String): String {
    val name = displayName.substringAfterLast('/').substringAfterLast('\\').ifBlank { "file" }
    if (name.substringAfterLast('.', "").isNotBlank()) return name
    val ext = when (mime.lowercase().substringBefore(';').trim()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/avif" -> "avif"
        "image/bmp" -> "bmp"
        else -> return name
    }
    return "$name.$ext"
}
