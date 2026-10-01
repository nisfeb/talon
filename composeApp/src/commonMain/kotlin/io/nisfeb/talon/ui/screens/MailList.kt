package io.nisfeb.talon.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.nisfeb.talon.ui.combinedClickableWithSecondary
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.VerticalDivider
import io.nisfeb.talon.mail.MailFolder
import io.nisfeb.talon.mail.InboxEntry
import io.nisfeb.talon.mail.MailAvailability
import io.nisfeb.talon.mail.MailRepo
import io.nisfeb.talon.mail.MailView
import io.nisfeb.talon.mail.Verdict
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.shortRelativeTime
import io.nisfeb.talon.util.nowMs
import kotlinx.coroutines.launch
import io.nisfeb.talon.ui.icons.TalonIcons

/**
 * The mailbox: one page of threads, read from the ship.
 *
 * Signed mail is only worth having if a reader can see what the ship
 * concluded about a signature, so the verdict is on the row and not
 * only inside the thread. The row is the surface people scan fastest,
 * and a forgery that only announces itself after you open it has
 * announced itself too late.
 */
@Composable
fun MailList(
    repo: MailRepo,
    contacts: ContactMap,
    /** Shown as "me" among a thread's people. */
    ourShip: String? = null,
    onOpenThread: (threadId: String) -> Unit,
    onCompose: (() -> Unit)? = null,
    onOpenDraft: ((io.nisfeb.talon.mail.Draft) -> Unit)? = null,
    /** Open again a message that did not go, its text and files with it. */
    onReopen: ((io.nisfeb.talon.mail.MailRepo.Unsent) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val availability by repo.availability.collectAsState()
    val page by repo.page.collectAsState()
    val loading by repo.loading.collectAsState()
    val error by repo.error.collectAsState()
    val unsent by repo.unsent.collectAsState()
    val outbox by repo.outbox.collectAsState()
    val problem by repo.problem.collectAsState()
    val drafts by repo.drafts.collectAsState()
    val labels by repo.knownLabels.collectAsState()
    val folder by repo.folder.collectAsState()
    val query by repo.query.collectAsState()
    val hasMore by repo.hasMore.collectAsState()
    var organising by remember { mutableStateOf(false) }
    val installer = io.nisfeb.talon.mail.LocalGrubberyInstall.current
    var installing by remember { mutableStateOf(false) }
    var installProblem by remember { mutableStateOf<String?>(null) }
    var pickingFolder by remember { mutableStateOf(false) }
    // Threads picked for one action on them all. Only those still listed
    // count: an archived one leaves the inbox, and with it the selection.
    var picked by remember(folder, query) { mutableStateOf(emptySet<String>()) }
    val listed = page?.threads.orEmpty()
    val chosen = remember(picked, listed) { picked.intersect(listed.map { it.id }.toSet()) }
    var confirmingDelete by remember { mutableStateOf(false) }
    io.nisfeb.talon.ui.PlatformBackHandler(enabled = chosen.isNotEmpty()) { picked = emptySet() }
    // The selection read at the tap, never closed over: a row keeps the
    // first handler it was given, so a toggle that captured `chosen` saw
    // the selection as it stood when that row was drawn, and each box
    // picked only itself.
    val toggle: (String) -> Unit = { id -> picked = picked.let { if (id in it) it - id else it + id } }
    if (confirmingDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete ${chosen.size} thread${if (chosen.size == 1) "" else "s"}?") },
            text = { Text("They are deleted from your ship. This cannot be undone.") },
            confirmButton = {
                io.nisfeb.talon.ui.TextButton(onClick = {
                    repo.deleteMany(chosen); picked = emptySet(); confirmingDelete = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { io.nisfeb.talon.ui.TextButton(onClick = { confirmingDelete = false }) { Text("Keep") } },
        )
    }

    if (organising) {
        MailOrganiseSheet(repo = repo, onDismiss = { organising = false })
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        // A mail client has a mailbox column. Where there is room it is
        // simply there; where there is not, the same list arrives as a
        // sheet from the toolbar rather than being crushed into chips
        // along the top.
        //
        // Four hundred dip was not room. Most phones clear it, so they
        // were drawing a mailbox column down the left at the same time
        // as the app's own hamburger sat above it — two left-hand
        // navigations for one screen, and a message list in whatever
        // was left. This is the width at which the app considers itself
        // to have a second column at all.
        val roomForColumn = maxWidth >= io.nisfeb.talon.ui.ExpandedThreshold

        Row(Modifier.fillMaxSize()) {
            if (roomForColumn) {
                MailFolderColumn(
                    selected = folder,
                    labels = labels,
                    onSelect = { repo.selectFolder(it) },
                    onOrganise = { organising = true },
                    modifier = Modifier.width(158.dp).fillMaxHeight(),
                )
                VerticalDivider()
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (chosen.isNotEmpty()) MailSelectionBar(
                    count = chosen.size,
                    allPicked = chosen.size == listed.size,
                    // Unarchive where everything picked is archived already.
                    archiving = listed.filter { it.id in chosen }.any { !it.archived },
                    onClear = { picked = emptySet() },
                    onAll = { picked = listed.map { it.id }.toSet() },
                    onArchive = { a -> repo.archiveMany(chosen, a); picked = emptySet() },
                    onRead = { r -> repo.markManyRead(chosen, r); picked = emptySet() },
                    onDelete = { confirmingDelete = true },
                ) else MailToolbar(
                    title = if (query.isNotEmpty()) "Results for \"$query\"" else folderName(folder),
                    query = query,
                    onSearch = { repo.search(it) },
                    loading = loading,
                    onRefresh = {
                        scope.launch {
                            if (folder is MailFolder.Drafts) repo.refreshDrafts()
                            else repo.refresh(asked = true)
                        }
                    },
                    onCompose = onCompose,
                    onFolders = if (roomForColumn) null else ({ pickingFolder = true }),
                )
                HorizontalDivider()
                error?.let { MailNotice(it, onDismiss = repo::clearError) }
                // A send that failed after its composer was closed.
                outbox.forEach { MailSendingLine(it) }
                unsent.forEach { u ->
                    MailNotice(
                        u.line,
                        onDismiss = { repo.dismiss(u) },
                        action = onReopen?.let { reopen -> "Open" to { repo.dismiss(u); reopen(u) } },
                    )
                }
                problem?.let { MailNotice(it, onDismiss = repo::clearProblem) }
                MailBody(
                    installing = installing,
                    installProblem = installProblem,
                    onInstall = if (installer == null) null else ({
                        installing = true
                        installProblem = null
                        scope.launch {
                            installer().fold(
                                onSuccess = { installing = false; repo.refresh() },
                                onFailure = { installing = false; installProblem = it.message },
                            )
                        }
                    }),
                    hasMore = hasMore,
                    onMore = { scope.launch { repo.loadMore() } },
                    folder = folder,
                    availability = availability,
                    page = page,
                    drafts = drafts,
                    loading = loading,
                    contacts = contacts,
                    ourShip = ourShip,
                    onOpenThread = { id -> if (chosen.isNotEmpty()) toggle(id) else onOpenThread(id) },
                    onOpenDraft = onOpenDraft,
                    picked = chosen,
                    onPick = toggle,
                )
            }
        }

        if (pickingFolder) {
            MailFolderSheet(
                selected = folder,
                labels = labels,
                onSelect = { repo.selectFolder(it); pickingFolder = false },
                onOrganise = { pickingFolder = false; organising = true },
                onDismiss = { pickingFolder = false },
            )
        }
    }
}

@Composable
private fun MailBody(
    installing: Boolean,
    installProblem: String?,
    onInstall: (() -> Unit)?,
    hasMore: Boolean,
    onMore: () -> Unit,
    folder: MailFolder,
    availability: MailAvailability,
    page: io.nisfeb.talon.mail.InboxPage?,
    drafts: List<io.nisfeb.talon.mail.Draft>,
    loading: Boolean,
    contacts: ContactMap,
    ourShip: String?,
    onOpenThread: (String) -> Unit,
    onOpenDraft: ((io.nisfeb.talon.mail.Draft) -> Unit)?,
    picked: Set<String> = emptySet(),
    onPick: (String) -> Unit = {},
) {
    when {
        folder is MailFolder.Drafts -> if (drafts.isEmpty()) {
            MailAbsent("Nothing half-written.")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(drafts, key = { it.id }) { d ->
                    DraftRow(d, onOpen = { onOpenDraft?.invoke(d) })
                    HorizontalDivider(modifier = Modifier.padding(start = 12.dp))
                }
            }
        }

        // Mail is a stock desk of the Grubbery shell: missing with the
        // shell, or only not fetched yet. One install does either.
        availability == MailAvailability.NO_GRUBBERY || availability == MailAvailability.NOT_FETCHED ->
            MailAbsent(
                when {
                    installing ->
                        "Fetching Grubbery's apps. They arrive over the network, " +
                            "which takes a few minutes."
                    installProblem != null -> installProblem
                    availability == MailAvailability.NO_GRUBBERY ->
                        "Mail runs in Grubbery, which this ship does not have yet."
                    else -> "Grubbery is here, but not its Mail yet."
                },
                actionLabel = when {
                    installing -> null
                    onInstall == null -> null
                    installProblem != null -> "Try again"
                    availability == MailAvailability.NO_GRUBBERY -> "Install Grubbery"
                    else -> "Fetch Mail"
                },
                onAction = onInstall,
            )

        page == null && loading ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

        page?.threads.isNullOrEmpty() && availability == MailAvailability.PRESENT ->
            MailAbsent(emptyLineFor(folder))

        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(page?.threads.orEmpty(), key = { it.id }) { row ->
                MailRow(
                    row = row,
                    people = mailPeople(row, ourShip) { contacts.displayName(it) },
                    onClick = { onOpenThread(row.id) },
                    picking = picked.isNotEmpty(),
                    picked = row.id in picked,
                    onPick = { onPick(row.id) },
                )
                HorizontalDivider(modifier = Modifier.padding(start = 12.dp))
            }
            if (hasMore) {
                item(key = "__more") {
                    io.nisfeb.talon.ui.TextButton(
                        onClick = onMore,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Show more") }
                }
            } else if ((page?.total ?: 0) > (page?.threads?.size ?: 0)) {
                // The ship will not answer past its own ceiling, so the
                // honest thing is to say what is not being shown rather
                // than offer a control that would do nothing.
                item(key = "__capped") {
                    Text(
                        "Showing ${page?.threads?.size} of ${page?.total}. " +
                            "Search to reach the rest.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                    )
                }
            }
        }
    }
}

internal fun folderName(f: MailFolder): String = when (f) {
    is MailFolder.View -> when (f.view) {
        MailView.INBOX -> "Inbox"
        MailView.SENT -> "Sent"
        MailView.ARCHIVED -> "Archived"
        MailView.ALL -> "All mail"
        MailView.LABEL -> "Labelled"
    }
    MailFolder.Drafts -> "Drafts"
    is MailFolder.Label -> f.name
}

private fun emptyLineFor(f: MailFolder): String = when (f) {
    is MailFolder.View -> when (f.view) {
        MailView.SENT -> "Nothing sent yet."
        MailView.ARCHIVED -> "Nothing archived."
        MailView.LABEL -> "Nothing with this label."
        else -> "No mail."
    }
    MailFolder.Drafts -> "Nothing half-written."
    is MailFolder.Label -> "Nothing labelled ${f.name}."
}

@Composable
private fun MailToolbar(
    title: String,
    query: String,
    onSearch: (String) -> Unit,
    loading: Boolean,
    onRefresh: () -> Unit,
    onCompose: (() -> Unit)?,
    onFolders: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onFolders != null) {
            // Not a hamburger. The app's own is one, and two identical
            // ones stacked down the left of a mail screen is a way of
            // asking somebody to guess which is which.
            IconButton(onClick = onFolders) {
                Icon(TalonIcons.Inbox, contentDescription = "Mailboxes")
            }
        }
        var searching by remember(query.isEmpty()) { mutableStateOf(query.isNotEmpty()) }
        var draft by remember(query) { mutableStateOf(query) }
        if (searching) {
            androidx.compose.material3.OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Search all mail") },
                singleLine = true,
                trailingIcon = {
                    IconButton(
                        onClick = {
                            if (draft.isBlank()) { searching = false; onSearch("") } else onSearch(draft)
                        },
                    ) { Icon(Icons.Filled.Search, contentDescription = "Search") }
                },
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSearch = { onSearch(draft) },
                ),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                ),
                modifier = Modifier.weight(1f).padding(end = 4.dp),
            )
            IconButton(onClick = { draft = ""; searching = false; onSearch("") }) {
                Icon(Icons.Filled.Close, contentDescription = "Clear search")
            }
        } else {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f).padding(start = if (onFolders != null) 0.dp else 8.dp),
            )
            IconButton(onClick = { searching = true }) {
                Icon(Icons.Filled.Search, contentDescription = "Search mail")
            }
        }
        if (onCompose != null) {
            io.nisfeb.talon.ui.TextButton(onClick = onCompose) { Text("New") }
        }
        // The reader always knows better than a ten-minute timer, so the
        // manual ask is a control and not a hidden gesture.
        IconButton(onClick = onRefresh, enabled = !loading) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Refresh, contentDescription = "Check for new mail")
            }
        }
    }
}

@Composable
private fun MailNotice(text: String, onDismiss: (() -> Unit)? = null, action: Pair<String, () -> Unit>? = null) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth().then(if (onDismiss != null) Modifier.clickable(onClick = onDismiss) else Modifier),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text + if (onDismiss != null) " Tap to dismiss." else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 8.dp),
            )
            action?.let { (label, act) -> io.nisfeb.talon.ui.TextButton(onClick = act) { Text(label) } }
        }
    }
}

/** A message on its way, with where it has got to: the composer is gone by now. */
@Composable
private fun MailSendingLine(s: io.nisfeb.talon.mail.MailRepo.Sending) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                "Sending " + s.subject.ifBlank { "a message" }.let { if (s.subject.isBlank()) it else "\"$it\"" } +
                    if (s.stage == "Sending" || s.stage == "Saving") "…" else " · ${s.stage}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun MailAbsent(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.size(12.dp))
                io.nisfeb.talon.ui.TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun MailRow(
    row: InboxEntry,
    people: String,
    onClick: () -> Unit,
    /** A selection is being made: every row shows its box. */
    picking: Boolean = false,
    picked: Boolean = false,
    /** Long-press, or right-click: start a selection, or add to it. */
    onPick: () -> Unit = {},
) {
    // Unread has to be seen at a glance down a long list, which a
    // slightly heavier weight was not: a dot in its own gutter, bold,
    // the time in the accent, and the read rows around it quieter.
    val weight = if (row.unread) FontWeight.Bold else FontWeight.Normal
    val readInk = if (row.unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    // Scanned by the dozen with a mouse, read by thumb on a phone: the
    // phone gets the chat list's sizes and its density setting's spacing.
    val touch = io.nisfeb.talon.ui.isTouchPrimary
    val type = MaterialTheme.typography
    val nameStyle = if (touch) type.titleMedium else type.bodySmall
    val subjectStyle = if (touch) type.bodyMedium else type.bodySmall
    val detailStyle = if (touch) type.bodyMedium else type.labelSmall
    val timeStyle = if (touch) type.labelMedium else type.labelSmall
    // Not a ListItem. Mail rows are scanned by the dozen, and the
    // three-slot list item is built for one line of each with generous
    // vertical padding — which put the timestamp floating in the middle
    // of a tall cell instead of on the line it belongs to.
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (picked) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickableWithSecondary(onClick = onClick, onLongClick = onPick)
            .padding(
                start = if (touch) 8.dp else 4.dp,
                end = if (touch) 16.dp else 12.dp,
                top = if (touch) io.nisfeb.talon.ui.LocalChatDensity.current.listRowVertical else 5.dp,
                bottom = if (touch) io.nisfeb.talon.ui.LocalChatDensity.current.listRowVertical else 5.dp,
            ),
    ) {
        if (picking) {
            androidx.compose.material3.Checkbox(
                checked = picked,
                onCheckedChange = { onPick() },
                modifier = Modifier.size(if (touch) 32.dp else 24.dp).semantics { contentDescription = "Select ${row.subject.ifBlank { "(no subject)" }}" },
            )
        }
        // Every row keeps the gutter, so read and unread names line up.
        Box(Modifier.width(8.dp).padding(top = if (touch) 8.dp else 4.dp)) {
            if (row.unread) MenuBadgeDot()
        }
        Column(
            Modifier.weight(1f).padding(start = if (touch) 8.dp else 6.dp),
            verticalArrangement = Arrangement.spacedBy(if (touch) 2.dp else 0.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    people,
                    style = nameStyle.copy(fontWeight = weight),
                    color = readInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = true),
                )
                // A thread carrying any forged copy says so here, even when
                // the summary above it was drawn from an honest one.
                if (row.forged || row.verdict == Verdict.FORGED) {
                    VerdictTag("FORGED", MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                } else if (row.verdict == Verdict.UNVERIFIED) {
                    VerdictTag("UNVERIFIED", MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                }
                if (row.last > 0) {
                    Text(
                        shortRelativeTime(row.last, nowMs()),
                        style = if (row.unread) timeStyle.copy(fontWeight = FontWeight.Bold) else timeStyle,
                        color = if (row.unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                row.subject.ifBlank { "(no subject)" },
                style = subjectStyle.copy(fontWeight = weight),
                color = readInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The preview is what an unread row is for. A read one has been
            // seen, so it gives its line back to the rows below it.
            if (row.snippet.isNotBlank() && row.unread) {
                Text(
                    row.snippet,
                    style = detailStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // A thread whose only copies this build cannot read still gets a
            // row: one silently missing from the listing is the failure that
            // count exists to prevent.
            if (row.unreadable > 0) {
                Text(
                    unreadableLine(row),
                    style = if (touch) type.bodySmall else type.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Who is on a thread, for its row: the latest sender, everyone else, and us last as "me". */
internal fun mailPeople(row: InboxEntry, ourShip: String?, nameFor: (String) -> String): String {
    val ships = (listOf(row.from) + row.participants).filter { it.isNotBlank() }.distinct()
    val me = if (ourShip != null && ourShip in ships) listOf("me") else emptyList()
    return (ships.filter { it != ourShip }.map(nameFor) + me).joinToString(", ").ifEmpty { nameFor(row.from) }
}

internal fun unreadableLine(row: InboxEntry): String {
    val n = row.unreadable
    val copies = if (n == 1) "1 message" else "$n messages"
    return if (row.count == 0) {
        "$copies here, none of them in a form this build can read."
    } else {
        "$copies here in a form this build cannot read."
    }
}

@Composable
internal fun VerdictTag(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(3.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun DraftRow(d: io.nisfeb.talon.mail.Draft, onOpen: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Text(
            if (d.to.isEmpty()) "No recipients yet" else d.to.joinToString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            d.subject.ifBlank { "(no subject)" },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (d.body.isNotBlank()) {
            Text(
                d.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * In place of the toolbar while threads are picked: how many, all of
 * them, and what can be done to them all.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MailSelectionBar(
    count: Int,
    allPicked: Boolean,
    /** Archive, rather than bring back: something picked is not archived yet. */
    archiving: Boolean,
    onClear: () -> Unit,
    onAll: () -> Unit,
    onArchive: (archived: Boolean) -> Unit,
    onRead: (read: Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClear) { Icon(Icons.Filled.Close, contentDescription = "Clear selection") }
            Text("$count selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (!allPicked) io.nisfeb.talon.ui.TextButton(onClick = onAll) { Text("Select all") }
        }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            io.nisfeb.talon.ui.TextButton(onClick = { onArchive(archiving) }) { Text(if (archiving) "Archive" else "Unarchive") }
            io.nisfeb.talon.ui.TextButton(onClick = { onRead(true) }) { Text("Mark read") }
            io.nisfeb.talon.ui.TextButton(onClick = { onRead(false) }) { Text("Mark unread") }
            io.nisfeb.talon.ui.TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        }
    }
}
