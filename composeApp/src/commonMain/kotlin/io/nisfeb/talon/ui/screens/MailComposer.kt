package io.nisfeb.talon.ui.screens

import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.selection.SelectionContainer
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
/**
 * What has been typed, kept apart from the screen showing it.
 *
 * A composer is taken out of composition by more than leaving it: a
 * window crossing a layout width, a rail tab, a section switch. Every
 * `remember` in it dies there, and the text went back to what the
 * intent said, which is nothing for a new message and, for a draft,
 * whatever it held when it was opened, written back over the newer
 * save on the way out. The intent carries this instead, so the edits
 * live exactly as long as the thing being written does.
 */
@androidx.compose.runtime.Stable
class MailEdits {
    var subject by mutableStateOf("")
    var body by mutableStateOf("")
    var recipientDraft by mutableStateOf("")
    val recipients = mutableStateListOf<String>()
    val files = mutableStateListOf<PickedImage>()
    var draftId: String = ""
        private set
    private var seeded = false

    /** Fill from [intent] the first time a composer opens on it. */
    fun seed(intent: MailIntent) {
        if (seeded) return
        seeded = true
        subject = intent.subject
        body = intent.body
        // In the field itself, not in chips beside it. A reply knows
        // who it is going to, and an empty box labelled To above a
        // filled-in subject reads as one still waiting to be answered.
        recipientDraft = intent.to.joinToString(" ")
        draftId = intent.draftId ?: io.nisfeb.talon.mail.newDraftId()
    }

    /**
     * Who it is going to: the chips, and what sits in the field not yet
     * made into one. A reply's recipients stay in the field until it is
     * sent, so anything that saves it has to read both.
     */
    val to: List<String> get() = (recipients + parseRecipients(recipientDraft).first).distinct()

    /**
     * Nothing written anywhere. The way out keeps a draft unless this
     * holds and the back button drops one only when it does, so the
     * two have to agree, which they cannot if each spells it out.
     */
    val isBlank: Boolean get() =
        body.isBlank() && subject.isBlank() && recipients.isEmpty() && recipientDraft.isBlank()
}

data class MailIntent(
    val prev: String? = null,
    /** The thread [prev] is in, so a reply can show what it answers. */
    val threadId: String? = null,
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
    /**
     * The live edits, which outlive any one composer. Made with the
     * intent, so each thing being written has its own.
     */
    val edits: MailEdits = MailEdits(),
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
    /** A ship as the owner reads it; the raw name where the host has none. */
    nameFor: (String) -> String = { it },
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val pickFile = rememberAnyFilePicker()
    val lists by repo.lists.collectAsState()
    androidx.compose.runtime.LaunchedEffect(repo) { repo.refreshLists() }

    // Seeded once per thing being written, and kept across every
    // remount of the screen that shows it.
    val edits = intent.edits
    remember(intent) { edits.seed(intent) }
    val recipients = edits.recipients
    // A fresh mail starts at To; a reply already has one, so it starts
    // at the message.
    val toFocus = remember { FocusRequester() }
    val bodyFocus = remember { FocusRequester() }
    LaunchedEffect(intent) {
        // A field cannot take focus before it is attached, and on a
        // cold open this effect can beat the layout to it. The failure
        // is silent and leaves the composer with no cursor in it, so
        // wait a frame, and ask again if the first ask was too early.
        val want = if (intent.to.isEmpty()) toFocus else bodyFocus
        if (runCatching { want.requestFocus() }.isFailure) {
            withFrameNanos {}
            runCatching { want.requestFocus() }
        }
    }
    val files = edits.files
    var sending by remember(intent) { mutableStateOf(false) }
    var progress by remember(intent) { mutableStateOf<String?>(null) }
    var problem by remember(intent) { mutableStateOf<String?>(null) }
    // Set once what is here has been accounted for, sent or saved, so
    // that the composer going away afterwards does not file a message
    // that has just gone out as a draft.
    var filed by remember(intent) { mutableStateOf(false) }
    // The thread being answered, read once here: the disclosure counts
    // off it, and the quoted conversation below is it.
    var thread by remember(intent.threadId) {
        mutableStateOf(intent.threadId?.let(repo::cachedThread))
    }
    LaunchedEffect(intent.threadId, intent.prev) {
        // A draft says which message it answers and not which thread
        // that is in, since auspex keeps neither with it, so the thread
        // is looked for among the ones this install has read.
        val id = intent.threadId ?: intent.prev?.let { repo.threadFor(it) } ?: return@LaunchedEffect
        if (thread == null) thread = repo.storedThread(id)
        repo.loadThread(id)?.let { thread = it }
    }

    // A file too big to attach, waiting on the owner's answer about
    // sending it as a link instead.
    var oversize by remember(intent) { mutableStateOf<PickedImage?>(null) }

    /** What is in the composer now, as a draft. */
    fun asDraft() = io.nisfeb.talon.mail.Draft(
        id = edits.draftId,
        // Typed but not yet committed still counts: the recipients of a
        // reply sit in the field until it is sent, and a draft saved on
        // the way out must not be the one that forgets them.
        to = edits.to,
        subject = edits.subject,
        body = edits.body,
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
            if (!filed && !edits.isBlank) {
                repo.keepDraft(asDraft())
            }
        }
    }

    fun commitRecipients(): List<String> {
        val (good, bad) = parseRecipients(edits.recipientDraft)
        good.forEach { if (it !in recipients) recipients += it }
        edits.recipientDraft = bad.joinToString(" ")
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
                    // Closing is instant. What is written is kept by the
                    // dispose above, on the repo's scope; waiting here for
                    // the save and the re-read that follows it meant the
                    // back button sat through two requests to the ship
                    // before the screen would move.
                    if (edits.isBlank && intent.draftId != null) {
                        // Opened from a draft and emptied out: keeping
                        // the husk would say there is still something
                        // to send.
                        repo.dropDraft(intent.draftId)
                    }
                    onCancel()
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
                        edits.recipientDraft.isNotBlank() -> Unit
                        to.isEmpty() -> problem = "Say who this is going to."
                        edits.body.isBlank() -> problem = "Nothing to send."
                        else -> {
                            sending = true
                            problem = null
                            val errorBefore = repo.error.value
                            fun why(fallback: String) =
                                repo.error.value?.takeIf { it != errorBefore } ?: fallback
                            scope.launch {
                                // The text reaches the ship before anything
                                // else is tried. A send cut short by leaving
                                // the screen has then left it somewhere, and
                                // the dispose need not file it a second time.
                                if (!repo.saveDraft(asDraft())) {
                                    problem = why("The message did not reach the ship; nothing was sent.")
                                    progress = null
                                    sending = false
                                    return@launch
                                }
                                filed = true
                                val refs = mutableListOf<io.nisfeb.talon.mail.AttachRef>()
                                var failed: String? = null
                                // A copy: the list is the screen's own, and
                                // removing a file while an upload waits threw
                                // out of the iterator and left the composer
                                // stuck on "Uploading 2 of 5" for good.
                                val outgoing = files.toList()
                                // One at a time, and named in the progress,
                                // because "uploading 2 of 5" is only true if
                                // there is one in flight.
                                outgoing.forEachIndexed { i, f ->
                                    if (failed != null) return@forEachIndexed
                                    progress = "Uploading ${i + 1} of ${outgoing.size}"
                                    val hash = runCatching { repo.uploadBlob(f.bytes) }
                                        .getOrElse { failed = "${f.displayName}: ${it.message ?: "the upload gave no reason"}"; null }
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
                                    filed = false
                                    sending = false
                                    return@launch
                                }
                                progress = "Sending"
                                // Every send without files goes out AS the
                                // draft just saved. The ship deletes a draft
                                // only once the send has landed, so a draft
                                // still standing afterwards is a poke that was
                                // taken and never applied: the one failure a
                                // poke cannot report, and the reason this
                                // route exists. A message with files goes
                                // direct, since no draft carries them, and its
                                // text is what the draft holds until it lands.
                                val sent: Boolean? = if (refs.isEmpty()) {
                                    repo.sendDraft(edits.draftId)
                                } else {
                                    repo.send(to, edits.subject, edits.body, intent.prev, refs).also {
                                        // The message carries the text now.
                                        // Dropped on the repo's scope, so
                                        // leaving cannot leave the husk.
                                        if (it) repo.dropDraft(edits.draftId)
                                    }
                                }
                                progress = null
                                when (sent) {
                                    true -> onSent()
                                    // Taken and not yet seen to go. The draft
                                    // is the ship's now: filing it again, or a
                                    // second Send, could put the message out
                                    // twice. So it stays filed, and Send stays
                                    // off, and the owner is told where to look.
                                    null -> {
                                        problem = "The ship took the message and has not confirmed it went. If it did not, it is in Drafts."
                                        return@launch
                                    }
                                    false -> {
                                        filed = false
                                        problem = why("The ship did not take the message.")
                                    }
                                }
                                // Last, so that the button cannot be pressed
                                // again while the draft is still being dropped.
                                sending = false
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
                // Counted off the thread rather than remembered from the
                // intent: a draft comes back from the ship with neither
                // the count nor the forward flag, since auspex keeps
                // neither, and the notice then said "this message alone
                // travels with this reply" over a send that still
                // carried the whole chain. Who has not seen it is read
                // off the recipients as they are chosen, so adding a
                // stranger to a plain reply says so too.
                val path = remember(thread, intent.prev) {
                    val id = intent.prev
                    if (thread == null || id == null) emptyList()
                    else io.nisfeb.talon.mail.pathTo(io.nisfeb.talon.mail.threadTree(thread!!.messages), id)
                }
                val seenIt = thread?.participants.orEmpty().toSet()
                val strangers = recipients.count { it !in seenIt }
                when {
                    path.isNotEmpty() -> TravelNotice(path.size, intent.forwarding || strangers > 0)
                    intent.travels > 0 -> TravelNotice(intent.travels, intent.forwarding || strangers > 0)
                    // Neither the thread nor a count in hand, which is a
                    // draft whose thread this install no longer holds.
                    // What travels is unknown, so it is not called small:
                    // the reassuring sentence is the one thing that must
                    // never be said without knowing.
                    else -> Text(
                        "This reply carries the conversation it answers to whoever you name.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (recipients.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    recipients.forEach { r ->
                        AssistChip(onClick = { recipients.remove(r) }, label = { Text(r) })
                    }
                }
            }
            OutlinedTextField(
                value = edits.recipientDraft,
                onValueChange = { edits.recipientDraft = it },
                label = { Text("To") },
                placeholder = { Text("~sampel-palnet") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(toFocus),
            )
            // A pick is a recipient at once, as a chip, with whatever
            // else was typed before it.
            io.nisfeb.talon.ui.ShipSuggestions(
                edits.recipientDraft,
                onPick = { edits.recipientDraft = it; commitRecipients() },
                separator = " ",
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
                value = edits.subject,
                onValueChange = { edits.subject = it },
                label = { Text("Subject") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = edits.body,
                onValueChange = { edits.body = it },
                label = { Text("Message") },
                minLines = 8,
                modifier = Modifier.fillMaxWidth().focusRequester(bodyFocus),
            )

            // What is being answered, under the message the way a reply
            // is read: shown, because writing a reply to something you
            // cannot see is guesswork, and foldable, because a long
            // thread would otherwise push the composer off the screen.
            thread?.let { Answering(it, intent.prev, nameFor) }

            if (files.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    files.forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${f.displayName} · ${sizeLabel(f.bytes.size.toLong())}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(enabled = !sending, onClick = { files.remove(f) }) { Text("Remove") }
                        }
                    }
                }
            }
            TextButton(
                enabled = files.size < AuspexApi.MAX_ATTACHMENTS && !sending,
                onClick = {
                    scope.launch {
                        val picked = runCatching { pickFile() }
                            .getOrElse { problem = it.message ?: "The file could not be read."; null } ?: return@launch
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
                                    edits.body = edits.body.trimEnd() + (if (edits.body.isBlank()) "" else "\n\n") + url
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


/**
 * The conversation a reply answers, under the message being written.
 * Open to start with: the point of it is to be read while writing.
 */
@Composable
private fun Answering(
    thread: io.nisfeb.talon.mail.MailThread,
    answering: String?,
    nameFor: (String) -> String,
) {
    var open by remember(thread.id) { mutableStateOf(true) }
    val messages = thread.messages
    if (messages.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider()
        TextButton(onClick = { open = !open }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
            Text(
                (if (open) "Hide" else "Show") + " the conversation (" + messages.size + ")",
                style = MaterialTheme.typography.labelLarge,
            )
        }
        if (open) {
            messages.sortedBy { it.sent }.forEach { m ->
                Column(Modifier.fillMaxWidth().padding(start = 8.dp)) {
                    Text(
                        nameFor(m.from) + " · " + io.nisfeb.talon.ui.shortRelativeTime(m.sent, io.nisfeb.talon.util.nowMs()) +
                            (if (m.id == answering) " · the one you are answering" else ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (m.id == answering) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SelectionContainer {
                        Text(
                            m.body.trim(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
