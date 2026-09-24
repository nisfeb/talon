package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.focus.focusProperties
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
                            // The send owns the text from here, on the repo's
                            // scope, so leaving does not stop it and the
                            // dispose must not file it again behind it (a
                            // draft saved after the send dropped it came back).
                            filed = true
                            // A copy: the list is the screen's own, and
                            // removing a file while an upload waits threw
                            // out of the iterator.
                            val outgoing = files.map { io.nisfeb.talon.mail.MailRepo.Outgoing(it.bytes, it.displayName, it.mimeType) }
                            val sent = repo.sendMessage(asDraft().copy(to = to), outgoing) { progress = it }
                            scope.launch {
                                val failed = sent.await()
                                if (failed == null) {
                                    onSent()
                                } else {
                                    // Said here, so not again on the list.
                                    repo.clearSendProblem()
                                    // Leaving now files what is written, which
                                    // may have changed since.
                                    filed = false
                                    problem = failed.replaceFirstChar { it.uppercase() }
                                    sending = false
                                }
                            }
                        }
                    }
                },
            ) {
                // By the button, not under the message: a long body put
                // the progress line below the screen.
                if (sending) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("Sending")
                } else {
                    Text("Send")
                }
            }
        }
        // Where the sending stands, and what went wrong, under the bar
        // for the same reason: at the end of a long body nobody saw them.
        progress?.let {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
        problem?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        HorizontalDivider()

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Counted off the thread rather than remembered from the
            // intent: a draft comes back from the ship with neither the
            // count nor the forward flag, since auspex keeps neither.
            // Who has not seen it is read off the recipients as they are
            // chosen, so adding a stranger to a plain reply says so too.
            val path = remember(thread, intent.prev) {
                val id = intent.prev
                if (thread == null || id == null) emptyList()
                else io.nisfeb.talon.mail.pathTo(io.nisfeb.talon.mail.threadTree(thread!!.messages), id)
            }
            val seenIt = thread?.participants.orEmpty().toSet()
            val strangers = recipients.count { it !in seenIt }
            // Until the thread is in hand (loading, or a draft whose thread
            // this install no longer holds) what travels is said from the
            // count the reply was opened with; with the thread, the message
            // itself is shown below and says it. With neither, what travels
            // is unknown, so it is not called small: the reassuring
            // sentence is the one thing never said without knowing.
            if (intent.prev != null && path.isEmpty()) {
                if (intent.travels > 0) {
                    TravelNotice(intent.travels, intent.forwarding || strangers > 0)
                } else {
                    Text(
                        "This reply carries the conversation it answers to whoever you name.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // Who it is going to, by name under a To: a tap opens their
            // card, the cross takes them off. A tap used to remove them,
            // which nothing about a chip says.
            if (recipients.isNotEmpty()) {
                val openProfile = io.nisfeb.talon.ui.LocalOpenProfile.current
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "To",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.align(Alignment.CenterVertically).padding(end = 2.dp),
                    )
                    recipients.forEach { r ->
                        androidx.compose.material3.InputChip(
                            selected = false,
                            onClick = { openProfile?.invoke(r) },
                            label = { Text(nameFor(r), maxLines = 1) },
                            trailingIcon = {
                                Icon(
                                    androidx.compose.material.icons.Icons.Filled.Close,
                                    contentDescription = "Remove ${nameFor(r)}",
                                    modifier = Modifier.size(16.dp).clickable(enabled = !sending) { recipients.remove(r) },
                                )
                            },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = edits.recipientDraft,
                onValueChange = { edits.recipientDraft = it },
                label = { Text(if (recipients.isEmpty()) "To" else "Add another") },
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
            // What is being answered, above where the answer is written:
            // a reply written to something out of sight is written to
            // the memory of it.
            if (path.isNotEmpty()) {
                Answering(
                    path = path,
                    forwarding = intent.forwarding,
                    strangers = strangers,
                    nameFor = nameFor,
                    onQuote = { lines ->
                        edits.body = quoteInto(edits.body, lines)
                        runCatching { bodyFocus.requestFocus() }
                    },
                )
            }
            OutlinedTextField(
                value = edits.body,
                onValueChange = { edits.body = it },
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
 * The message being answered, and above it, folded, the ones that
 * travel with it: a reply carries its whole branch, signed, and the
 * sender should be able to read what they are handing on. On a forward,
 * or to somebody new to the thread, that it goes to people who have not
 * seen it is said in the error color, since that is the part that
 * cannot be taken back.
 *
 * The answered message is a read-only field, so a selection in it can
 * be quoted into the reply: its lines, whole, each set with "> ".
 */
@Composable
private fun Answering(
    path: List<io.nisfeb.talon.mail.MailMessage>,
    forwarding: Boolean,
    strangers: Int,
    nameFor: (String) -> String,
    onQuote: (String) -> Unit,
) {
    val answered = path.last()
    val earlier = path.dropLast(1)
    var open by remember(answered.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (forwarding) "Forwarding " else "Replying to ") + nameFor(answered.from) + " · " +
                    io.nisfeb.talon.ui.shortRelativeTime(answered.sent, io.nisfeb.talon.util.nowMs()),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            if (earlier.isNotEmpty()) {
                TextButton(onClick = { open = !open }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                    Text(
                        if (open) "Hide included messages"
                        else "Show ${earlier.size} included message${if (earlier.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        if (forwarding || strangers > 0) {
            Text(
                (if (path.size == 1) "This signed message goes" else "These ${path.size} signed messages go") +
                    " to people who have not seen ${if (path.size == 1) "it" else "them"}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (open) {
            earlier.forEach { m ->
                Column(Modifier.fillMaxWidth().padding(start = 8.dp)) {
                    Text(
                        nameFor(m.from) + " · " + io.nisfeb.talon.ui.shortRelativeTime(m.sent, io.nisfeb.talon.util.nowMs()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SelectionContainer {
                        Text(m.body.trim(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        val body = answered.body.trim()
        var field by remember(answered.id) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(body)) }
        androidx.compose.foundation.text.BasicTextField(
            value = field,
            // Read-only: only the selection moves.
            onValueChange = { field = it.copy(text = body) },
            readOnly = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(10.dp),
        )
        val picked = field.selection
        TextButton(
            enabled = !picked.collapsed,
            onClick = {
                onQuote(wholeLines(body, picked.min, picked.max))
                field = field.copy(selection = androidx.compose.ui.text.TextRange(picked.max))
            },
            // Pressed with a mouse, a button that takes focus takes it from
            // the text above, which drops the selection it is to quote and
            // disables the button before the click lands.
            modifier = Modifier.focusProperties { canFocus = false },
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(if (picked.collapsed) "Select lines above to quote them" else "Quote", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** The whole lines a selection from [start] to [end] touches. */
internal fun wholeLines(text: String, start: Int, end: Int): String {
    val from = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (it < 0 || start == 0) 0 else it + 1 }
    val to = text.indexOf('\n', (end - 1).coerceAtLeast(0)).let { if (it < 0) text.length else it }
    return text.substring(from.coerceAtMost(to), to)
}

/**
 * [lines] quoted into [body]: each line set with "> ", after what is
 * already written, with a blank line either side so the answer to it
 * goes under it.
 */
internal fun quoteInto(body: String, lines: String): String {
    val quote = lines.trimEnd().lines().joinToString("\n") { if (it.isBlank()) ">" else "> $it" }
    val before = body.trimEnd()
    return (if (before.isEmpty()) "" else "$before\n\n") + quote + "\n\n"
}
