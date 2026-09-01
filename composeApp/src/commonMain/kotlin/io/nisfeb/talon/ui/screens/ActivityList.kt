package io.nisfeb.talon.ui.screens
import io.nisfeb.talon.util.formatMonthDayTime

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.ui.Avatar
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.contactMapFlow
import io.nisfeb.talon.urbit.StoryCache
import io.nisfeb.talon.urbit.TlonChatRepo

/**
 * The list body of [ActivityFeedScreen], extracted so the desktop /
 * tablet-landscape rail can render it without the screen-level
 * header. Mobile + compact-mode wide go through [ActivityFeedScreen]
 * which wraps this with the existing TopAppBar + back-arrow.
 */
@Composable
fun ActivityList(
    db: AppDatabase,
    repo: TlonChatRepo,
    onOpenConversation: (whom: String) -> Unit,
    /** Open [whom] and route into the thread for [parentId], anchored
     *  on [replyId]. Used for reply / mention-in-reply rows so taps
     *  land on the exact reply rather than just the chat. */
    onOpenReply: (whom: String, parentId: String, replyId: String) -> Unit = { w, _, _ -> onOpenConversation(w) },
    /** Open [whom] anchored on a top-level [postId] (post-mentions).
     *  Callers wire this like the Bookmarks/Search focus-message path
     *  so the tap lands on the mentioned message, not the chat bottom. */
    onOpenPost: (whom: String, postId: String) -> Unit = { w, _ -> onOpenConversation(w) },
    modifier: Modifier = Modifier,
) {
    val contactMap by remember {
        contactMapFlow(
            db.contacts().stream(),
            db.clubs().stream(),
            db.groups().streamGroups(),
            db.groups().streamChannelGroups(),
        )
    }.collectAsState(initial = ContactMap.EMPTY)

    // Bind to the repo's cached StateFlow so re-mounts (rail-tab
    // switching, navigating in and out of Activity) render instantly
    // from last-known-good. Null = never loaded; non-null (incl.
    // empty list) = at least one fetch has completed.
    val cached by repo.activityFeedFlow.collectAsState()
    var tab by rememberSaveable { mutableStateOf(TlonChatRepo.ActivityTab.ALL) }
    val items = cached?.forTab(tab) ?: emptyList()
    var refreshing by remember { mutableStateOf(cached == null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Bumped by the error-state Retry button; re-keys the fetch effect.
    // Without it a failed first load was terminal on the desktop rail,
    // where the tab stays mounted and the effect never re-ran.
    var retryCount by remember { mutableStateOf(0) }

    // Always kick a background refresh on mount. With the cache in
    // place this isn't blocking — UI shows last-known immediately and
    // upgrades when the fetch lands. Errors only surface if there's
    // nothing cached to fall back on.
    LaunchedEffect(retryCount) {
        refreshing = true
        error = null
        runCatching { repo.fetchActivityFeed() }
            .onFailure { error = it.message ?: it::class.simpleName }
        refreshing = false
    }

    Column(modifier = modifier) {
        // The ship computes `mentions` and `replies` itself and hands
        // all three views back in the one feed/init scry — switching
        // tabs is free, no refetch.
        if (cached != null) {
            TabRow(selectedTabIndex = tab.ordinal) {
                TlonChatRepo.ActivityTab.entries.forEach { t ->
                    Tab(
                        selected = t == tab,
                        onClick = { tab = t },
                        text = { Text(t.label, style = MaterialTheme.typography.labelLarge) },
                    )
                }
            }
        }
        // Thin progress bar above the list while a background refresh
        // is in flight AND we already have cached content to show
        // underneath. The first-ever load uses the centered spinner
        // below — this indicator is for "you're seeing slightly stale
        // data, fresh is on the way." Hides itself the moment the
        // refresh completes.
        if (refreshing && cached != null) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
            )
        }
        when {
            // First-ever load (no cache yet) — show the spinner.
            cached == null && refreshing -> Row(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }

            // Error AND nothing cached to render. If we have cached
            // data, swallow the error silently — stale > broken.
            cached == null && error != null -> Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "Couldn't load activity. Check your connection.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { retryCount++ }) { Text("Retry") }
            }

            items.isEmpty() -> Text(
                when (tab) {
                    TlonChatRepo.ActivityTab.ALL ->
                        "No activity yet. Mentions and replies to your posts will show up here."
                    TlonChatRepo.ActivityTab.MENTIONS -> "Nobody has mentioned you yet."
                    TlonChatRepo.ActivityTab.REPLIES -> "No replies to your posts yet."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                // Raw activity events sometimes share whom + sentMs +
                // kind (e.g. multiple "Posted" rows with no reliable time);
                // include the list index in the key so LazyColumn can
                // still dedupe cleanly.
                itemsIndexed(
                    items = items,
                    key = { i, it -> "$i:${it.whom}:${it.sentMs}:${it.kind}" },
                ) { _, item ->
                    ActivityRow(item, contactMap) {
                        val w = item.whom ?: return@ActivityRow
                        val parent = item.parentPostId
                        val post = item.postId
                        if (parent != null && post != null) {
                            onOpenReply(w, parent, post)
                        } else if (post != null) {
                            onOpenPost(w, post)
                        } else {
                            onOpenConversation(w)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(
    item: TlonChatRepo.ActivityFeedItem,
    contactMap: ContactMap,
    onClick: () -> Unit,
) {
    val authorLabel = item.author?.let { contactMap.displayName(it) }
    val preview = remember(item.contentJson) {
        item.contentJson?.let {
            // Reuse StoryCache by keying off the concatenated identity
            // so different events hitting the same content string share.
            StoryCache.textFor("activity:${it.hashCode()}", it)
                .replace('\n', ' ')
                .take(200)
        }
    }
    val timestamp = remember(item.sentMs) {
        if (item.sentMs > 0) formatMonthDayTime(item.sentMs) else ""
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(
            label = authorLabel ?: item.title,
            url = item.author?.let { contactMap.avatar(it) },
            size = 40.dp,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val header = buildString {
                append(authorLabel ?: item.author ?: "someone")
                append(" · ")
                append(item.kind)
                append(" · ")
                append(item.title)
            }
            Text(
                header,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!preview.isNullOrBlank()) {
                Text(preview, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
            }
            if (timestamp.isNotEmpty()) {
                Text(
                    timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

