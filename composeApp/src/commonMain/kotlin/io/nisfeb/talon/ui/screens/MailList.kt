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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    onInstall: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val availability by repo.availability.collectAsState()
    val page by repo.page.collectAsState()
    val loading by repo.loading.collectAsState()
    val error by repo.error.collectAsState()
    val view by repo.view.collectAsState()

    Column(modifier.fillMaxSize()) {
        MailToolbar(
            view = view,
            loading = loading,
            onView = { repo.setView(it) },
            onRefresh = { scope.launch { repo.refresh() } },
        )
        HorizontalDivider()

        error?.let { MailNotice(it) }

        when {
            availability == MailAvailability.NO_GRUBBERY ->
                MailAbsent(
                    "Mail runs inside Grubbery, which this ship does not have yet.",
                    actionLabel = if (onInstall != null) "Install Grubbery" else null,
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
                MailAbsent(emptyLineFor(view), actionLabel = null, onAction = null)

            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(page?.threads.orEmpty(), key = { it.id }) { row ->
                    MailRow(
                        row = row,
                        nameFor = { contacts.displayName(it) },
                        onClick = { onOpenThread(row.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

private fun emptyLineFor(view: MailView): String = when (view) {
    MailView.INBOX -> "No mail."
    MailView.SENT -> "Nothing sent yet."
    MailView.ARCHIVED -> "Nothing archived."
    MailView.ALL -> "No mail."
    MailView.LABEL -> "Nothing with this label."
}

@Composable
private fun MailToolbar(
    view: MailView,
    loading: Boolean,
    onView: (MailView) -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(TABS, key = { it.first.name }) { (v, label) ->
                FilterChip(
                    selected = view == v,
                    onClick = { onView(v) },
                    label = { Text(label) },
                )
            }
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

private val TABS = listOf(
    MailView.INBOX to "Inbox",
    MailView.SENT to "Sent",
    MailView.ARCHIVED to "Archived",
    MailView.ALL to "All",
)

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
private fun MailAbsent(text: String, actionLabel: String?, onAction: (() -> Unit)?) {
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
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        overlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    nameFor(row.from),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = weight),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // A thread carrying any forged copy says so here, even when
                // the summary above it was drawn from an honest one.
                if (row.forged || row.verdict == Verdict.FORGED) {
                    Spacer(Modifier.width(6.dp))
                    VerdictTag("FORGED", MaterialTheme.colorScheme.error)
                } else if (row.verdict == Verdict.UNVERIFIED) {
                    Spacer(Modifier.width(6.dp))
                    VerdictTag("UNVERIFIED", MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                if (row.last > 0) {
                    Text(
                        shortRelativeTime(row.last, nowMs()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        headlineContent = {
            Text(
                row.subject.ifBlank { "(no subject)" },
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = weight),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                if (row.snippet.isNotBlank()) {
                    Text(
                        row.snippet,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // A thread whose only copies this build cannot read still
                // gets a row: one silently vanishing from the listing is
                // the failure the count exists to prevent.
                if (row.unreadable > 0) {
                    Text(
                        unreadableLine(row),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
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
