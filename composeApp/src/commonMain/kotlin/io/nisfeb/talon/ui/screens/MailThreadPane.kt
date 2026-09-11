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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    /** The thread left this view: archived, deleted, or marked unread.
     *  The reader cannot keep showing something the listing no longer
     *  has, and on a wide layout there is no back arrow to do it. */
    onGone: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var thread by remember(threadId) { mutableStateOf<MailThread?>(null) }
    var gone by remember(threadId) { mutableStateOf(false) }
    var loading by remember(threadId) { mutableStateOf(true) }
    var drawn by remember(threadId) { mutableStateOf(false) }
    // Folded subtrees, and messages read down to their header line.
    var filed by remember(threadId) { mutableStateOf<String?>(null) }
    val folded = remember(threadId) { mutableStateListOf<String>() }
    val shut = remember(threadId) { mutableStateListOf<String>() }
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

    val knownLabels by repo.knownLabels.collectAsState()
    val forest = remember(thread) { threadTree(thread?.messages.orEmpty()) }
    val hasBranches = remember(forest) { branches(forest) }
    // Whatever is selected, defaulting to the newest honest message —
    // the same rule a flat reading used, so the default never moves.
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
            labels = thread?.labels.orEmpty(),
            known = knownLabels,
            onLabel = { l, add ->
                scope.launch {
                    repo.setLabel(threadId, l, add)
                    thread = repo.loadThread(threadId)
                }
            },
            showTree = hasBranches,
            drawn = drawn,
            onMode = { drawn = it },
            onBack = onBack,
            archived = thread?.archived == true,
            onArchive = {
                scope.launch {
                    repo.setArchived(threadId, thread?.archived != true)
                    onGone()
                }
            },
            onMarkUnread = {
                scope.launch {
                    // The newest message is the one whose state the row
                    // reads, so unread means that one.
                    val newest = thread?.messages.orEmpty().maxByOrNull { it.sent }
                    if (newest != null) repo.markUnread(listOf(newest.id))
                    onGone()
                }
            },
            onDelete = {
                scope.launch {
                    repo.deleteThread(threadId)
                    onGone()
                }
            },
            onFile = {
                val t = thread ?: return@MailThreadHeader
                scope.launch {
                    filed = "Filing…"
                    val url = repo.publishToLattice(
                        title = t.messages.firstOrNull()?.subject.orEmpty()
                            .ifBlank { "Mail thread" },
                        seed = io.nisfeb.talon.mail.MailGemtext.seedFor(threadId, null),
                        gemtext = io.nisfeb.talon.mail.MailGemtext.thread(
                            t,
                            nameFor = { contacts.displayName(it) },
                            when_ = { shortRelativeTime(it, nowMs()) },
                        ),
                    )
                    filed = url?.let { "Filed to Lattice at $it" }
                        ?: repo.error.value ?: "Could not file it."
                }
            },
        )
        HorizontalDivider()
        filed?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clickable { filed = null },
            )
        }

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
                TravelLine(travelling.size)
                HorizontalDivider()
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
                if (drawn) {
                    // The picture, and under it the message it selects.
                    MailThreadTree(
                        messages = thread!!.messages,
                        selected = selected ?: answering,
                        nameFor = { contacts.displayName(it) },
                        onSelect = { selected = it },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    HorizontalDivider()
                    val shown = thread!!.messages.firstOrNull {
                        it.id == (selected ?: answering)
                    }
                    if (shown != null) {
                        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                            item(key = shown.id) {
                                MailMessageCard(
                                    node = io.nisfeb.talon.mail.MailNode(shown),
                                    depth = 0,
                                    onPath = false,
                                    selected = false,
                                    selectable = false,
                                    hidden = 0,
                                    foldable = false,
                                    folded = false,
                                    onFold = {},
                                    shut = false,
                                    onShut = {},
                                    nameFor = { contacts.displayName(it) },
                                    onSelect = {},
                                    onFile = {
                                        scope.launch {
                                            filed = "Filing…"
                                            val url = repo.publishToLattice(
                                                title = shown.subject.ifBlank { "Mail" },
                                                seed = io.nisfeb.talon.mail.MailGemtext
                                                    .seedFor(threadId, shown.id),
                                                gemtext = io.nisfeb.talon.mail.MailGemtext.message(
                                                    shown,
                                                    nameFor = { contacts.displayName(it) },
                                                    when_ = { shortRelativeTime(it, nowMs()) },
                                                ),
                                            )
                                            filed = url?.let { "Filed to Lattice at $it" }
                                                ?: repo.error.value ?: "Could not file it."
                                        }
                                    },
                                    repo = repo,
                                )
                            }
                        }
                    }
                } else LazyColumn(Modifier.fillMaxSize()) {
                    items(
                        io.nisfeb.talon.mail.flattenVisible(forest, folded.toSet()),
                        key = { it.node.message.id },
                    ) { v ->
                        val node = v.node
                        MailMessageCard(
                            node = node,
                            depth = v.depth,
                            hidden = v.hidden,
                            foldable = node.children.isNotEmpty(),
                            folded = node.message.id in folded,
                            onFold = {
                                if (!folded.remove(node.message.id)) folded.add(node.message.id)
                            },
                            shut = node.message.id in shut,
                            onShut = {
                                if (!shut.remove(node.message.id)) shut.add(node.message.id)
                            },
                            onPath = node.message.id in travelling.map { it.id },
                            selected = node.message.id == selected,
                            selectable = node.message.verdict != Verdict.FORGED,
                            nameFor = { contacts.displayName(it) },
                            onSelect = { selected = node.message.id },
                            onFile = {
                                scope.launch {
                                    filed = "Filing…"
                                    val m = node.message
                                    val url = repo.publishToLattice(
                                        title = m.subject.ifBlank { "Mail" },
                                        seed = io.nisfeb.talon.mail.MailGemtext
                                            .seedFor(threadId, m.id),
                                        gemtext = io.nisfeb.talon.mail.MailGemtext.message(
                                            m,
                                            nameFor = { contacts.displayName(it) },
                                            when_ = { shortRelativeTime(it, nowMs()) },
                                        ),
                                    )
                                    filed = url?.let { "Filed to Lattice at $it" }
                                        ?: repo.error.value ?: "Could not file it."
                                }
                            },
                            repo = repo,
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/**
 * What can be done to the whole thread. All of it is local: no other
 * ship sees an archive or a read mark, which is why each one refreshes
 * the listing itself rather than waiting to be told.
 *
 * Delete asks first. It is the one action here that destroys evidence,
 * and a signed message is exactly the kind of thing somebody wants back.
 */
@Composable
private fun ThreadActions(
    archived: Boolean,
    onArchive: () -> Unit,
    onMarkUnread: () -> Unit,
    onDelete: () -> Unit,
    onFile: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Thread actions")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(if (archived) "Move to inbox" else "Archive") },
                onClick = { open = false; onArchive() },
            )
            DropdownMenuItem(
                text = { Text("Mark unread") },
                onClick = { open = false; onMarkUnread() },
            )
            DropdownMenuItem(
                text = { Text("File to Lattice") },
                onClick = { open = false; onFile() },
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = { open = false; confirming = true },
            )
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Delete this thread?") },
            text = {
                Text(
                    "Every copy on this ship goes, including any forged one " +
                        "kept as evidence. Nobody else's copy is touched.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirming = false; onDelete() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Keep") }
            },
        )
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
    labels: List<String>,
    known: List<String>,
    onLabel: (String, Boolean) -> Unit,
    archived: Boolean,
    onArchive: () -> Unit,
    onMarkUnread: () -> Unit,
    onDelete: () -> Unit,
    onFile: () -> Unit,
    showTree: Boolean,
    drawn: Boolean,
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
            ThreadActions(
                archived = archived,
                onArchive = onArchive,
                onMarkUnread = onMarkUnread,
                onDelete = onDelete,
                onFile = onFile,
            )
            // Offered only where there is a tree to see. A straight
            // thread has nothing the two modes would show differently.
            if (showTree) {
                FilterChip(
                    selected = !drawn,
                    onClick = { onMode(false) },
                    label = { Text("Messages") },
                )
                Spacer(Modifier.width(6.dp))
                FilterChip(
                    selected = drawn,
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
        MailLabelRow(labels = labels, known = known, onToggle = onLabel)
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
    hidden: Int,
    foldable: Boolean,
    folded: Boolean,
    onFold: () -> Unit,
    shut: Boolean,
    onShut: () -> Unit,
    onPath: Boolean,
    selected: Boolean,
    selectable: Boolean,
    nameFor: (String) -> String,
    onSelect: () -> Unit,
    onFile: () -> Unit,
    repo: MailRepo,
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
            .padding(start = (12 + depth * 14).dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The twisty folds the conversation UNDER this message. A
            // reply that swallows itself is not what a reader means by
            // folding a thread.
            if (foldable) {
                IconButton(onClick = onFold, modifier = Modifier.size(22.dp)) {
                    Icon(
                        if (folded) Icons.AutoMirrored.Filled.KeyboardArrowRight
                        else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (folded) "Unfold replies" else "Fold replies",
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(2.dp))
            } else {
                Spacer(Modifier.width(24.dp))
            }
            Text(
                nameFor(m.from),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(onClick = onShut),
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
            TextButton(onClick = onFile) { Text("File", style = MaterialTheme.typography.labelSmall) }
        }
        if (hidden > 0) {
            Text(
                if (hidden == 1) "1 reply folded" else "$hidden replies folded",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 24.dp),
            )
        }
        if (node.orphaned) {
            Text(
                "Answers a message this build cannot read.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp),
            )
        }
        // Shut reads the message down to its header, the way a read
        // message collapses in a mail client. The subject line stays so
        // the row still says what it is.
        if (shut) {
            Text(
                m.body.lineSequence().firstOrNull().orEmpty().take(120),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 24.dp),
            )
            return@Column
        }
        if (m.verdict == Verdict.FORGED) {
            Text(
                "This copy's signature does not match its contents. It is kept as " +
                    "evidence and cannot be answered.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 24.dp, top = 2.dp),
            )
        }
        Column(Modifier.padding(start = 24.dp, top = 6.dp)) {
            SelectionContainer {
                Text(m.body, style = MaterialTheme.typography.bodyMedium)
            }
            // The author's rendering instruction is signed, which proves
            // they chose it and not that it is safe. Plain text is what
            // we render; saying so beats quietly ignoring the request.
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
                    m.attachments.forEach { a ->
                        MailAttachmentRow(repo = repo, attachment = a, from = m.from)
                    }
                }
            }
        }
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
