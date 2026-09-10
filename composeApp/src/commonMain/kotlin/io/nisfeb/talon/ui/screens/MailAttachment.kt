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
import io.nisfeb.talon.mail.MailRepo
import io.nisfeb.talon.ui.LocalImageDownloader
import io.nisfeb.talon.ui.SaveResult
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

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
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

            is AttachState.Held -> TextButton(
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
            ) { Text("Save") }

            else -> TextButton(
                onClick = {
                    scope.launch {
                        state = AttachState.Working("Looking for it")
                        state = pull(repo, attachment, from) { state = AttachState.Working(it) }
                    }
                },
            ) { Text(if (state is AttachState.Failed) "Try again" else "Get") }
        }
    }
}

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
    val held = runCatching { repo.blob(a.hash, a.name, a.mime) }.getOrNull()
    if (held != null) return AttachState.Held(held.bytes, held.name)

    say("Asking the network")
    repo.fetchBlob(a.hash, from)
    repeat(POLLS) {
        delay(POLL_MS)
        say("Waiting for it (${it + 1} of $POLLS)")
        val now = runCatching { repo.blob(a.hash, a.name, a.mime) }.getOrNull()
        if (now != null) return AttachState.Held(now.bytes, now.name)
    }
    return AttachState.Failed(
        "No copy came back. Whoever holds it may be offline; it can be asked for again.",
    )
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
    is AttachState.Held -> "Ready to save · ${sizeLabel(a.size)}"
    is AttachState.Saved -> "Saved to ${state.location}"
    is AttachState.Failed -> state.message
}
