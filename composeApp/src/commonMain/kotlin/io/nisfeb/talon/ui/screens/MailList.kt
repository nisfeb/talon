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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.material.icons.filled.Menu
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
    onOpenThread: (threadId: String) -> Unit,
    onCompose: (() -> Unit)? = null,
    onOpenDraft: ((io.nisfeb.talon.mail.Draft) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val availability by repo.availability.collectAsState()
    val page by repo.page.collectAsState()
    val loading by repo.loading.collectAsState()
    val error by repo.error.collectAsState()
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
                MailToolbar(
                    title = if (query.isNotEmpty()) "Results for \"$query\"" else folderName(folder),
                    query = query,
                    onSearch = { repo.search(it) },
                    loading = loading,
                    onRefresh = {
                        scope.launch {
                            if (folder is MailFolder.Drafts) repo.refreshDrafts()
                            else repo.refresh()
                        }
                    },
                    onCompose = onCompose,
                    onFolders = if (roomForColumn) null else ({ pickingFolder = true }),
                )
                HorizontalDivider()
                error?.let { MailNotice(it) }
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
                    onOpenThread = onOpenThread,
                    onOpenDraft = onOpenDraft,
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
    onOpenThread: (String) -> Unit,
    onOpenDraft: ((io.nisfeb.talon.mail.Draft) -> Unit)?,
) {
    when {
        folder is MailFolder.Drafts -> if (drafts.isEmpty()) {
            MailAbsent("Nothing half-written.", actionLabel = null, onAction = null)
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(drafts, key = { it.id }) { d ->
                    DraftRow(d, onOpen = { onOpenDraft?.invoke(d) })
                    HorizontalDivider(modifier = Modifier.padding(start = 12.dp))
                }
            }
        }

        availability == MailAvailability.NO_GRUBBERY ->
            MailAbsent(
                when {
                    installing ->
                        "Installing Grubbery. The desk arrives over the network, " +
                            "which takes a moment."
                    installProblem != null -> installProblem
                    else -> "Mail runs inside Grubbery, which this ship does not have yet."
                },
                actionLabel = when {
                    installing -> null
                    onInstall == null -> null
                    installProblem != null -> "Try again"
                    else -> "Install Grubbery"
                },
                onAction = onInstall,
            )

        availability == MailAvailability.OLD_GRUBBERY ->
            MailAbsent(
                "This ship's Grubbery predates Mail. It updates itself from " +
                    "its publisher; check back shortly.",
                actionLabel = null,
                onAction = null,
            )

        page == null && loading ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

        page?.threads.isNullOrEmpty() && availability == MailAvailability.PRESENT ->
            MailAbsent(emptyLineFor(folder), actionLabel = null, onAction = null)

        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(page?.threads.orEmpty(), key = { it.id }) { row ->
                MailRow(
                    row = row,
                    nameFor = { contacts.displayName(it) },
                    onClick = { onOpenThread(row.id) },
                )
                HorizontalDivider(modifier = Modifier.padding(start = 12.dp))
            }
            if (hasMore) {
                item(key = "__more") {
                    androidx.compose.material3.TextButton(
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
            IconButton(onClick = onFolders) {
                Icon(Icons.Filled.Menu, contentDescription = "Mailboxes")
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
            androidx.compose.material3.TextButton(onClick = onCompose) { Text("New") }
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
private fun MailNotice(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
internal fun MailAbsent(text: String, actionLabel: String?, onAction: (() -> Unit)?) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.size(12.dp))
                androidx.compose.material3.TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun MailRow(row: InboxEntry, nameFor: (String) -> String, onClick: () -> Unit) {
    val weight = if (row.unread) FontWeight.SemiBold else FontWeight.Normal
    // Not a ListItem. Mail rows are scanned by the dozen, and the
    // three-slot list item is built for one line of each with generous
    // vertical padding — which put the timestamp floating in the middle
    // of a tall cell instead of on the line it belongs to.
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                nameFor(row.from),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = weight),
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
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            row.subject.ifBlank { "(no subject)" },
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = weight),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // The preview is what an unread row is for. A read one has been
        // seen, so it gives its line back to the rows below it.
        if (row.snippet.isNotBlank() && row.unread) {
            Text(
                row.snippet,
                style = MaterialTheme.typography.labelSmall,
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
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
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
private fun VerdictTag(text: String, color: androidx.compose.ui.graphics.Color) {
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
