package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.mail.Attachment
import io.nisfeb.talon.mail.MailMessage
import io.nisfeb.talon.mail.MailNode
import io.nisfeb.talon.mail.MailRepo
import io.nisfeb.talon.mail.MailThread
import io.nisfeb.talon.mail.Verdict
import io.nisfeb.talon.mail.branches
import io.nisfeb.talon.mail.flatten
import io.nisfeb.talon.mail.newestAnswerable
import io.nisfeb.talon.mail.pathTo
import io.nisfeb.talon.mail.threadTree
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.shortRelativeTime
import io.nisfeb.talon.util.nowMs
import kotlinx.coroutines.launch

/**
 * One thread, read.
 *
 * The tree is here rather than in a later slice because it is not a
 * layout preference. A reply or forward ships the path from the root to
 * the message it answers and never the sibling branches, so selecting a
 * node and seeing its path lit is how a person knows what a send would
 * disclose. The count under the tree comes from that same path, so the
 * picture and the number cannot disagree.
 */
@Composable
fun MailThreadPane(
    repo: MailRepo,
    threadId: String,
    contacts: ContactMap,
    ourShip: String,
    onCompose: (MailIntent) -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var thread by remember(threadId) { mutableStateOf<MailThread?>(null) }
    var gone by remember(threadId) { mutableStateOf(false) }
    var loading by remember(threadId) { mutableStateOf(true) }
    var treeMode by remember(threadId) { mutableStateOf(false) }
    var selected by remember(threadId) { mutableStateOf<String?>(null) }

    LaunchedEffect(threadId) {
        loading = true
        val t = repo.loadThread(threadId)
        thread = t
        gone = t == null
        loading = false
        // Reading it is what marks it read, and the mark is invisible to
        // every other client, so nothing else would ever do it.
        val unread = t?.messages.orEmpty().filter { !it.read }.map { it.id }
        if (unread.isNotEmpty()) scope.launch { repo.markRead(unread) }
    }

    val forest = remember(thread) { threadTree(thread?.messages.orEmpty()) }
    val hasBranches = remember(forest) { branches(forest) }
    // In list mode a reply answers the newest honest message; in tree
    // mode it answers whatever is selected.
    val answering = remember(forest, selected, thread) {
        selected ?: newestAnswerable(thread?.messages.orEmpty())?.id
    }
    val travelling = remember(forest, answering) {
        answering?.let { pathTo(forest, it) }.orEmpty()
    }

    Column(modifier.fillMaxSize()) {
        MailThreadHeader(
            subject = thread?.messages?.firstOrNull()?.subject.orEmpty(),
            participants = thread?.participants.orEmpty().map { contacts.displayName(it) },
            unreadable = thread?.unreadable ?: 0,
            showTree = hasBranches,
            treeMode = treeMode,
            onMode = { treeMode = it },
            onBack = onBack,
        )
        HorizontalDivider()

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            gone -> MailAbsentLine("This thread is no longer on the ship.")

            thread!!.messages.isEmpty() -> MailAbsentLine(
                if (thread!!.unreadable > 0) {
                    "Every copy of this thread is in a form this build cannot read."
                } else {
                    "Nothing in this thread."
                },
            )

            else -> {
                if (treeMode) {
                    TravelLine(travelling.size)
                    HorizontalDivider()
                }
                MailThreadActions(
                    // A forged copy cannot be answered, so a thread of
                    // nothing else has nothing to reply to.
                    enabled = answering != null,
                    onReply = {
                        onCompose(
                            MailIntent(
                                prev = answering,
                                to = thread!!.participants.filter { it != ourShip },
                                subject = thread!!.messages.firstOrNull()?.subject.orEmpty(),
                                travels = travelling.size,
                            ),
                        )
                    },
                    onForward = {
                        onCompose(
                            MailIntent(
                                prev = answering,
                                to = emptyList(),
                                subject = thread!!.messages.firstOrNull()?.subject.orEmpty(),
                                travels = travelling.size,
                                forwarding = true,
                            ),
                        )
                    },
                )
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxSize()) {
                    items(flatten(forest), key = { it.first.message.id }) { (node, depth) ->
                        MailMessageCard(
                            node = node,
                            depth = if (treeMode) depth else 0,
                            onPath = treeMode && node.message.id in travelling.map { it.id },
                            selected = treeMode && node.message.id == selected,
                            selectable = treeMode && node.message.verdict != Verdict.FORGED,
                            nameFor = { contacts.displayName(it) },
                            onSelect = { selected = node.message.id },
                            onFetch = { a ->
                                scope.launch { repo.fetchBlob(a.hash, node.message.from) }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/**
 * Reply and forward. Both send from the message the reader is on: the
 * newest honest one in list mode, the selected node in tree mode. That
 * is what makes selecting a node and replying a deliberate act rather
 * than a surprise.
 */
@Composable
private fun MailThreadActions(enabled: Boolean, onReply: () -> Unit, onForward: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(onClick = onReply, enabled = enabled) { Text("Reply") }
        TextButton(onClick = onForward, enabled = enabled) { Text("Forward") }
    }
}

@Composable
private fun MailThreadHeader(
    subject: String,
    participants: List<String>,
    unreadable: Int,
    showTree: Boolean,
    treeMode: Boolean,
    onMode: (Boolean) -> Unit,
    onBack: (() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
            Text(
                subject.ifBlank { "(no subject)" },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f).padding(start = if (onBack != null) 4.dp else 8.dp),
            )
            // Offered only where there is a tree to see. A straight
            // thread has nothing the two modes would show differently.
            if (showTree) {
                FilterChip(
                    selected = !treeMode,
                    onClick = { onMode(false) },
                    label = { Text("List") },
                )
                Spacer(Modifier.width(6.dp))
                FilterChip(
                    selected = treeMode,
                    onClick = { onMode(true) },
                    label = { Text("Tree") },
                )
            }
        }
        if (participants.isNotEmpty()) {
            Text(
                participants.joinToString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (unreadable > 0) {
            Text(
                unreadableThreadLine(unreadable),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, top = 2.dp),
            )
        }
    }
}

internal fun unreadableThreadLine(n: Int): String =
    if (n == 1) "1 copy here is in a form this build cannot read."
    else "$n copies here are in a form this build cannot read."

/** What a reply or forward from the selected message would carry. Drawn
 *  from the same path the tree lights, because they are one fact. */
@Composable
private fun TravelLine(count: Int) {
    Text(
        when (count) {
            0 -> "Nothing selected."
            1 -> "1 signed message travels with a reply from here."
            else -> "$count signed messages travel with a reply from here."
        },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun MailAbsentLine(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MailMessageCard(
    node: MailNode,
    depth: Int,
    onPath: Boolean,
    selected: Boolean,
    selectable: Boolean,
    nameFor: (String) -> String,
    onSelect: () -> Unit,
    onFetch: (Attachment) -> Unit,
) {
    val m = node.message
    val ground = when {
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        onPath -> MaterialTheme.colorScheme.primary.copy(alpha = 0.045f)
        else -> Color.Transparent
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(ground)
            .then(if (selectable) Modifier.clickable(onClick = onSelect) else Modifier)
            .padding(start = (16 + depth * 14).dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                nameFor(m.from),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.width(8.dp))
            when (m.verdict) {
                Verdict.FORGED -> Tag("FORGED", MaterialTheme.colorScheme.error)
                Verdict.UNVERIFIED -> Tag("UNVERIFIED", MaterialTheme.colorScheme.onSurfaceVariant)
                Verdict.VERIFIED -> Unit
            }
            Spacer(Modifier.weight(1f))
            if (m.sent > 0) {
                Text(
                    shortRelativeTime(m.sent, nowMs()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (node.orphaned) {
            Text(
                "Answers a message this build cannot read.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (m.verdict == Verdict.FORGED) {
            Text(
                "This copy's signature does not match its contents. It is kept as " +
                    "evidence and cannot be answered.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.size(6.dp))
        SelectionContainer {
            Text(m.body, style = MaterialTheme.typography.bodyMedium)
        }
        // The author's rendering instruction is signed, which proves they
        // chose it and not that it is safe. Plain text is what we render;
        // saying so is better than quietly ignoring the request.
        if (m.bodyMime.isNotBlank() && m.bodyMime != "text/plain") {
            Text(
                "Shown as plain text; this message asked for ${m.bodyMime}.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (m.attachments.isNotEmpty()) {
            Spacer(Modifier.size(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                m.attachments.forEach { a -> AttachmentRow(a) { onFetch(a) } }
            }
        }
    }
}

/**
 * One attachment. Bytes are never pushed, so a file on a message we
 * hold and have not pulled is the ordinary state of an inbound one, and
 * the answer to it is a control rather than an error.
 */
@Composable
private fun AttachmentRow(a: Attachment, onFetch: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(a.name.ifBlank { a.hash }, style = MaterialTheme.typography.bodySmall)
            Text(
                // The type is the sender's claim about bytes we may not
                // even hold, so it is reported and never acted on.
                listOfNotNull(sizeLabel(a.size), a.mime.takeIf { it.isNotBlank() })
                    .joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onFetch) { Text("Fetch") }
    }
}

internal fun sizeLabel(bytes: Long): String = when {
    bytes <= 0 -> "unknown size"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}

@Composable
private fun Tag(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(3.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}
