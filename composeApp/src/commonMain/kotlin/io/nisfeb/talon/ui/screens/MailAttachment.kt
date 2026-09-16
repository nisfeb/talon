package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.mail.Attachment
import io.nisfeb.talon.mail.Blob
import io.nisfeb.talon.mail.MailRepo
import io.nisfeb.talon.ui.LocalImageDownloader
import io.nisfeb.talon.ui.SaveResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One attachment, and the whole of what it takes to get it.
 *
 * Bytes are never pushed. An attachment on a message we hold and have
 * not pulled is the ordinary state of an inbound one, and the ship
 * answers for it with its own status rather than an error. So one
 * control does both jobs: ask for the bytes, and if this ship has none,
 * ask the network and wait.
 *
 * Waiting is a poll because nothing announces the arrival either. The
 * ship probes a few holders on a deadline each, so about half a minute
 * is the honest window before saying it did not come.
 */
@Composable
fun MailAttachmentRow(
    repo: MailRepo,
    attachment: Attachment,
    from: String,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val downloader = LocalImageDownloader.current
    var state by remember(attachment.hash) { mutableStateOf<AttachState>(AttachState.Idle) }
    val isImage = attachment.mime.startsWith("image/", ignoreCase = true)
    val isInvite = attachment.mime.startsWith("text/calendar", ignoreCase = true) || attachment.name.endsWith(".ics", ignoreCase = true)
    val shipCalendar = io.nisfeb.talon.calendar.LocalCalendarRepo.current
    var inviteMenu by remember { mutableStateOf(false) }
    var invited by remember(attachment.hash) { mutableStateOf<String?>(null) }
    // A small picture is worth fetching unasked; it is what the
    // message is about, and the row still says where it stands.
    LaunchedEffect(attachment.hash) {
        if (isImage && attachment.size in 1..IMAGE_AUTO_MAX && state is AttachState.Idle) {
            state = AttachState.Working("Looking for it")
            state = pull(repo, attachment, from) { state = AttachState.Working(it) }
        }
    }
    Column(modifier) {
    // The claimed size decided whether to fetch unasked; the real one
    // decides whether to draw. A claim is cheap to lie about.
    (state as? AttachState.Held)?.takeIf { isImage && it.bytes.size.toLong() <= IMAGE_AUTO_MAX }?.let { held ->
        coil3.compose.AsyncImage(
            model = held.bytes,
            contentDescription = attachment.name,
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier.padding(bottom = 4.dp).widthIn(max = 480.dp).heightIn(max = 360.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
        )
    }
    Row(Modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                attachment.name.ifBlank { attachment.hash },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                statusLine(state, attachment),
                style = MaterialTheme.typography.labelSmall,
                color = when (state) {
                    is AttachState.Failed -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when (val s = state) {
            is AttachState.Working -> {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }

            is AttachState.Held-> if (downloader.canSaveFiles) TextButton(
                onClick = {
                    scope.launch {
                        state = AttachState.Working("Saving")
                        state = when (val r = downloader.saveBytes(s.name, s.bytes)) {
                            is SaveResult.Saved -> AttachState.Saved(r.location)
                            is SaveResult.Failed -> AttachState.Failed(r.message)
                            SaveResult.Unsupported ->
                                AttachState.Failed("Saving files is not wired on this platform.")
                        }
                    }
                },
            ) { Text("Save") } else Unit

            // Saved is terminal: the bytes are on disk, and offering
            // "Get" again would say they were not.
            is AttachState.Saved -> Unit

            else -> TextButton(
                onClick = {
                    scope.launch {
                        state = AttachState.Working("Looking for it")
                        state = pull(repo, attachment, from) { state = AttachState.Working(it) }
                    }
                },
            ) { Text(if (state is AttachState.Failed) "Try again" else "Get") }
        }
        // An invite goes straight into one of the ship's calendars, fetched first if need be.
        if (isInvite && shipCalendar != null && state !is AttachState.Working) {
            androidx.compose.foundation.layout.Box {
                TextButton(onClick = { inviteMenu = true }) { Text(invited ?: "Add to calendar") }
                androidx.compose.material3.DropdownMenu(expanded = inviteMenu, onDismissRequest = { inviteMenu = false }) {
                    shipCalendar.writable().forEach { c ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(c.name.ifBlank { c.id }) },
                            onClick = {
                                inviteMenu = false
                                scope.launch {
                                    invited = "Adding…"
                                    val bytes = (state as? AttachState.Held)?.bytes ?: run {
                                        state = AttachState.Working("Looking for it")
                                        state = pull(repo, attachment, from) { state = AttachState.Working(it) }
                                        (state as? AttachState.Held)?.bytes
                                    }
                                    invited = when {
                                        bytes == null -> "Could not fetch it"
                                        shipCalendar.importIcs(c.id, bytes.decodeToString()) -> "Added to ${c.name.ifBlank { c.id }}"
                                        else -> "Not added"
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
    }
}

/** Bytes a picture may cost without being asked for. Bounded by the
 *  ship's own blob cap: anything bigger cannot come from this ship
 *  anyway, so the sender-claimed size is never what lets a large
 *  fetch through (AuspexApi refuses over-cap blobs on read). */
private const val IMAGE_AUTO_MAX = 262_144L

/**
 * Ask for the bytes; if this ship has none, ask the network and wait
 * for them. One control, because from the reader's side "fetch" and
 * "download" are the same wish.
 */
private suspend fun pull(
    repo: MailRepo,
    a: Attachment,
    from: String,
    say: (String) -> Unit,
): AttachState {
    val held = fetching { repo.blob(a.hash, a.name, a.mime) }
    if (held != null) return AttachState.Held(held.bytes, held.name)

    say("Asking the network")
    repo.fetchBlob(a.hash, from)
    repeat(POLLS) {
        delay(POLL_MS)
        say("Waiting for it (${it + 1} of $POLLS)")
        val now = fetching { repo.blob(a.hash, a.name, a.mime) }
        if (now != null) return AttachState.Held(now.bytes, now.name)
    }
    return AttachState.Failed(
        "No copy came back. Whoever holds it may be offline; it can be asked for again.",
    )
}

/** One blob read where null means "not here" — never "the view went
 *  away", which a blanket runCatching would swallow mid-poll. */
private suspend fun fetching(block: suspend () -> Blob?): Blob? = try {
    block()
} catch (c: CancellationException) {
    throw c
} catch (e: Exception) {
    null
}

private const val POLLS = 15
private const val POLL_MS = 2_000L

internal sealed interface AttachState {
    data object Idle : AttachState
    data class Working(val what: String) : AttachState
    data class Held(val bytes: ByteArray, val name: String) : AttachState
    data class Saved(val location: String) : AttachState
    data class Failed(val message: String) : AttachState
}

internal fun statusLine(state: AttachState, a: Attachment): String = when (state) {
    AttachState.Idle -> listOfNotNull(
        sizeLabel(a.size),
        // The type is the sender's claim about bytes we may not even
        // hold, so it is reported and never acted on.
        a.mime.takeIf { it.isNotBlank() },
    ).joinToString(" · ")

    is AttachState.Working -> state.what
    // The bytes we hold, not the size the sender claimed for them.
    is AttachState.Held -> "Ready to save · ${sizeLabel(state.bytes.size.toLong())}"
    is AttachState.Saved -> "Saved to ${state.location}"
    is AttachState.Failed -> state.message
}
