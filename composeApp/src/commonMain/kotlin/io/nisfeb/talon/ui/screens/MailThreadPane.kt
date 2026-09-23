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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import io.nisfeb.talon.mail.MailNode
import io.nisfeb.talon.mail.MailRepo
import io.nisfeb.talon.mail.MailThread
import io.nisfeb.talon.mail.Verdict
import io.nisfeb.talon.mail.branches
import io.nisfeb.talon.mail.newestAnswerable
import io.nisfeb.talon.mail.pathTo
import io.nisfeb.talon.mail.threadTree
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.shortRelativeTime
import io.nisfeb.talon.util.nowMs
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import io.nisfeb.talon.ui.icons.TalonIcons

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
    // The last copy read this session shows at once; the ship's answer replaces it.
    var thread by remember(threadId) { mutableStateOf(repo.cachedThread(threadId)) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var loading by remember(threadId) { mutableStateOf(repo.cachedThread(threadId) == null) }
    var refreshing by remember(threadId) { mutableStateOf(false) }
    var drawn by remember(threadId) { mutableStateOf(false) }
    // Folded subtrees, and messages read down to their header line.
    var filed by remember(threadId) { mutableStateOf<String?>(null) }
    val folded = remember(threadId) { mutableStateListOf<String>() }
    val shut = remember(threadId) { mutableStateListOf<String>() }
    var selected by remember(threadId) { mutableStateOf<String?>(null) }
    // Remote images in a sender-controlled body are a read receipt and
    // an IP leak, so they never load unasked. One tap trusts the thread
    // — the sender is one party — and the remembering is per thread.
    var imagesShown by remember(threadId) { mutableStateOf(false) }

    io.nisfeb.talon.notify.ClearNotificationsWhileShown("mail:$threadId")
    LaunchedEffect(threadId) {
        // Not read this session: the copy an earlier one left on disk, if any.
        if (thread == null) thread = repo.storedThread(threadId)
        loading = thread == null
        refreshing = thread != null
        val t = repo.loadThread(threadId)
        // Out of reach with a copy on screen: keep the copy rather than call the thread gone.
        thread = if (t == null && repo.error.value != null && thread != null) thread else t
        loading = false
        refreshing = false
        // Reading it is what marks it read, and the mark is invisible to
        // every other client, so nothing else would ever do it.
        val unread = t?.messages.orEmpty().filter { !it.read }.map { it.id }
        if (unread.isNotEmpty()) {
            repo.markRead(unread, threadId)
            thread = repo.cachedThread(threadId) ?: thread
        }
    }

    // A refused write's rollback lands in the repo's caches, not in the
    // copy of the thread this pane holds — the label toggle above edits
    // both optimistically, and without this only the repo's half came
    // back. The counter, not the error text: two refusals with the same
    // reason are one emission there.
    LaunchedEffect(threadId) {
        repo.rollbacks.drop(1).collect {
            thread = repo.cachedThread(threadId) ?: thread
        }
    }

    val knownLabels by repo.knownLabels.collectAsState()
    val forest = remember(thread) { threadTree(thread?.messages.orEmpty()) }
    val copies = remember(thread) {
        io.nisfeb.talon.mail.copyCounts(thread?.messages.orEmpty())
    }
    val hasBranches = remember(forest) { branches(forest) }
    // Whatever is selected, defaulting to the newest honest message —
    // the same rule a flat reading used, so the default never moves.
    val answering = remember(forest, selected, thread) {
        selected ?: newestAnswerable(thread?.messages.orEmpty())?.id
    }
    val travelling = remember(forest, answering) {
        answering?.let { pathTo(forest, it) }.orEmpty()
    }
    val travellingIds = remember(travelling) { travelling.mapTo(HashSet()) { it.id } }
    val visible = remember(forest, folded.toList()) {
        io.nisfeb.talon.mail.flattenVisible(forest, folded.toSet())
    }

    val nameFor: (String) -> String = contacts::displayName
    fun whenAt(ms: Long) = shortRelativeTime(ms, nowMs())
    fun intent(forwarding: Boolean) = MailIntent(
        prev = answering,
        threadId = threadId,
        to = if (forwarding) emptyList() else thread?.participants.orEmpty().filter { it != ourShip },
        subject = answerSubject(thread?.messages?.firstOrNull()?.subject.orEmpty(), forwarding),
        travels = travelling.size,
        forwarding = forwarding,
    )

    // Filing is the same three lines whatever is being filed.
    fun file(title: String, seed: String, gemtext: String) {
        scope.launch {
            filed = "Filing…"
            filed = repo.publishToLattice(title = title, seed = seed, gemtext = gemtext)
                ?.let { "Filed to Lattice at $it" }
                ?: repo.error.value ?: "Could not file it."
        }
    }
    fun fileMessage(m: io.nisfeb.talon.mail.MailMessage) = file(
        title = m.subject.ifBlank { "Mail" },
        seed = io.nisfeb.talon.mail.MailGemtext.seedFor(threadId, m.id),
        gemtext = io.nisfeb.talon.mail.MailGemtext.message(m, nameFor, ::whenAt),
    )

    Column(modifier.fillMaxSize()) {
        MailThreadHeader(
            subject = thread?.messages?.firstOrNull()?.subject.orEmpty(),
            participants = thread?.participants.orEmpty().map(nameFor),
            unreadable = thread?.unreadable ?: 0,
            labels = thread?.labels.orEmpty(),
            known = knownLabels,
            onLabel = { l, add ->
                // Shown at once; the ship's copy follows the next time the thread is read.
                thread = thread?.let { t -> t.copy(labels = if (add) (t.labels + l).distinct() else t.labels - l) }
                repo.setLabel(threadId, l, add)
            },
            showTree = hasBranches,
            drawn = drawn,
            onMode = { drawn = it },
            onBack = onBack,
            archived = thread?.archived == true,
            // Each shows at once and writes in the background, so the view can leave straight away.
            onArchive = {
                repo.setArchived(threadId, thread?.archived != true)
                onGone()
            },
            onMarkUnread = {
                // The newest message is the one whose state the row
                // reads, so unread means that one.
                val newest = thread?.messages.orEmpty().maxByOrNull { it.sent }
                if (newest != null) repo.markUnread(listOf(newest.id), threadId)
                onGone()
            },
            onDelete = {
                repo.deleteThread(threadId)
                onGone()
            },
            onFile = {
                val t = thread ?: return@MailThreadHeader
                file(
                    title = t.messages.firstOrNull()?.subject.orEmpty().ifBlank { "Mail thread" },
                    seed = io.nisfeb.talon.mail.MailGemtext.seedFor(threadId, null),
                    gemtext = io.nisfeb.talon.mail.MailGemtext.thread(t, nameFor, ::whenAt),
                )
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

        // A copy is on screen and a fresher one is on its way.
        if (refreshing) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
        val t = thread
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            t == null -> MailAbsent("This thread is no longer on the ship.")

            t.messages.isEmpty() -> MailAbsent(
                if (t.unreadable > 0) {
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
                    onReply = { onCompose(intent(forwarding = false)) },
                    onForward = { onCompose(intent(forwarding = true)) },
                    onCopyLink = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(io.nisfeb.talon.urbit.TalonLink.forMail(threadId))) },
                )
                HorizontalDivider()
                if (drawn) {
                    // The picture, and under it the message it selects.
                    MailThreadTree(
                        messages = t.messages,
                        selected = answering,
                        nameFor = nameFor,
                        onSelect = { selected = it },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    HorizontalDivider()
                    // From the collapsed copies, the same ones the tree
                    // above was built from. The raw list can hold a
                    // forged copy ahead of the honest one, and taking
                    // the first would let a forger choose what the card
                    // under a node says.
                    val shown = remember(t) { io.nisfeb.talon.mail.collapse(t.messages) }.firstOrNull {
                        it.id == answering
                    }
                    if (shown != null) {
                        Column(
                            Modifier.weight(1f).fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            MailMessageCard(
                                node = io.nisfeb.talon.mail.MailNode(shown),
                                depth = 0,
                                hidden = 0,
                                copies = copies[shown.id] ?: 1,
                                nameFor = nameFor,
                                onFile = { fileMessage(shown) },
                                repo = repo,
                                imagesShown = imagesShown,
                                onShowImages = { imagesShown = true },
                            )
                        }
                    }
                } else LazyColumn(Modifier.fillMaxSize()) {
                    items(
                        visible,
                        key = { it.node.message.id },
                    ) { v ->
                        val node = v.node
                        MailMessageCard(
                            node = node,
                            depth = v.depth,
                            hidden = v.hidden,
                            copies = copies[node.message.id] ?: 1,
                            foldable = node.children.isNotEmpty(),
                            folded = node.message.id in folded,
                            onFold = {
                                if (!folded.remove(node.message.id)) folded.add(node.message.id)
                            },
                            shut = node.message.id in shut,
                            onShut = {
                                if (!shut.remove(node.message.id)) shut.add(node.message.id)
                            },
                            onPath = node.message.id in travellingIds,
                            selected = node.message.id == selected,
                            selectable = node.message.verdict != Verdict.FORGED,
                            nameFor = nameFor,
                            onSelect = { selected = node.message.id },
                            onFile = { fileMessage(node.message) },
                            repo = repo,
                            imagesShown = imagesShown,
                            onShowImages = { imagesShown = true },
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
    onLabels: () -> Unit,
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
                text = { Text("Labels…") },
                onClick = { open = false; onLabels() },
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
private fun MailThreadActions(enabled: Boolean, onReply: () -> Unit, onForward: () -> Unit, onCopyLink: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(onClick = onReply, enabled = enabled) { Text("Reply") }
        TextButton(onClick = onForward, enabled = enabled) { Text("Forward") }
        TextButton(onClick = onCopyLink) { Text("Copy link") }
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
    // Labelling is not common enough to hold the space above every
    // message: the editor is behind the menu, and only the labels a
    // thread has show here, a tap away from it.
    var labeling by remember { mutableStateOf(false) }
    if (labeling) {
        AlertDialog(
            onDismissRequest = { labeling = false },
            title = { Text("Labels") },
            text = { MailLabelRow(labels = labels, known = known, onToggle = onLabel) },
            confirmButton = { TextButton(onClick = { labeling = false }) { Text("Done") } },
        )
    }
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
                onLabels = { labeling = true },
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
        if (labels.isNotEmpty()) {
            Row(Modifier.padding(start = 8.dp, top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                labels.forEach { l -> AssistChip(onClick = { labeling = true }, label = { Text(l) }) }
            }
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

/** The subject a reply or forward opens with: marked, and never stacked
 *  — a reply to "Re: Plans" is "Re: Plans", not "Re: Re: Plans". */
internal fun answerSubject(subject: String, forwarding: Boolean): String {
    val prefix = if (forwarding) "Fwd: " else "Re: "
    if (subject.startsWith(prefix, ignoreCase = true)) return subject
    return if (subject.isBlank()) prefix.trimEnd() else prefix + subject
}

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
private fun MailMessageCard(
    node: MailNode,
    depth: Int,
    hidden: Int,
    copies: Int,
    nameFor: (String) -> String,
    foldable: Boolean = false,
    folded: Boolean = false,
    onFold: () -> Unit = {},
    shut: Boolean = false,
    onShut: () -> Unit = {},
    onPath: Boolean = false,
    selected: Boolean = false,
    selectable: Boolean = false,
    onSelect: () -> Unit = {},
    onFile: () -> Unit,
    repo: MailRepo,
    /** Whether remote images may load. False shows the affordance
     *  instead; true only after the reader asked, per thread. */
    imagesShown: Boolean = false,
    onShowImages: () -> Unit = {},
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
                Verdict.FORGED -> VerdictTag("FORGED", MaterialTheme.colorScheme.error)
                Verdict.UNVERIFIED -> VerdictTag("UNVERIFIED", MaterialTheme.colorScheme.onSurfaceVariant)
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
        // Several grubs under one id, differing only in signature, is the
        // shape a forgery arrives in. Worth saying even when the node
        // reads verified, because it is what the verdict was drawn from.
        if (copies > 1) {
            Text(
                "$copies stored copies of this message; the strongest verdict " +
                    "among them is shown.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp),
            )
        }
        if (m.verdict == Verdict.FORGED) {
            Text(
                "A copy of this message failed its signature. It is kept as " +
                    "evidence and cannot be answered.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 24.dp, top = 2.dp),
            )
        }
        Column(Modifier.padding(start = 24.dp, top = 6.dp)) {
            // Links open through the app's handler, so an urb:// address
            // lands in lattice as it does from a chat; the rest go out.
            SelectionContainer {
                Text(io.nisfeb.talon.ui.linkifyStatus(m.body), style = MaterialTheme.typography.bodyMedium)
            }
            val images = remember(m.body) { io.nisfeb.talon.ui.imageUrlsIn(m.body) }
            if (images.isNotEmpty()) {
                if (!imagesShown) {
                    // Loading these tells the sender's server — and
                    // whatever sits between — that this was read, and
                    // from where. So they load when asked, not before.
                    TextButton(onClick = onShowImages) {
                        Text(
                            if (images.size == 1) "Load 1 remote image"
                            else "Load ${images.size} remote images",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                } else {
                    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        images.take(6).forEach { url ->
                            coil3.compose.AsyncImage(
                                model = url,
                                contentDescription = url,
                                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                error = androidx.compose.ui.graphics.vector.rememberVectorPainter(TalonIcons.BrokenImage),
                                modifier = Modifier
                                    .widthIn(max = 480.dp).heightIn(max = 360.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { runCatching { uriHandler.openUri(url) } },
                            )
                        }
                    }
                }
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

internal fun sizeLabel(bytes: Long): String =
    if (bytes <= 0) "unknown size" else io.nisfeb.talon.util.humanFileSize(bytes)

