package io.nisfeb.talon.ui.screens
import io.nisfeb.talon.util.formatMonthDayClock

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import io.nisfeb.talon.ui.TextButton
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.NoticeEntity
import io.nisfeb.talon.notify.OpenNoticeRequests
import io.nisfeb.talon.ui.Avatar
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.urbit.StoryCache
import io.nisfeb.talon.urbit.TlonChatRepo

/**
 * The holistic Activity feed. One reverse-chron timeline of everything that
 * happened, filtered by domain chips: All, Chat (the ship's `%activity`:
 * mentions, replies, posts, reactions, folded into one timeline), then a
 * chip per non-chat domain that currently has a notice (Assistant for orrery
 * and loops, Account for armillary). Chat-adjacent notices (invites, DM
 * requests) fold into Chat. A notice row deep-links into its feature screen
 * and marks itself read.
 *
 * Extracted from [ActivityFeedScreen] so the desktop / tablet-landscape rail
 * can render it without the screen-level header.
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
    /** Open what a non-chat notice is about, by its deep-link tag. The
     *  default hands the tag to the same router a tapped push uses. */
    onOpenNotice: (target: String) -> Unit = { OpenNoticeRequests.request(it) },
    modifier: Modifier = Modifier,
) {
    val contactMap by io.nisfeb.talon.ui.rememberContactMap(db)
    val scope = rememberCoroutineScope()

    // Chat half: the ship's %activity feed, cached so a re-mount renders
    // last-known-good at once. Null = never loaded.
    val cached by repo.activityFeedFlow.collectAsState()
    val chatItems = cached?.forTab(TlonChatRepo.ActivityTab.ALL) ?: emptyList()

    // Non-chat half: the local notice store. Small (pruned), so the whole
    // list is read and the chips/counts derived from it in memory.
    val noticeDao = remember(db) { db.notices() }
    val notices by remember(db) { noticeDao.stream() }.collectAsState(initial = emptyList())

    // Followed threads (kept here until they move to Home).
    val threads by remember(db) {
        db.followedThreads().streamThreads().map { io.nisfeb.talon.data.threadsInOrder(it) }
    }.collectAsState(initial = emptyList())
    val unreadThreads = threads.count { it.unread > 0 }

    // Chips: All and Chat always; one per non-chat domain that has a notice;
    // Threads when any is followed. A domain chip vanishes when its last
    // notice is pruned, so the row only ever shows live content.
    val domainChips = remember(notices) { notices.map { it.domain }.filter { it != DOMAIN_CHAT }.distinct().sorted() }
    val unreadByDomain = remember(notices) { notices.filter { !it.read }.groupingBy { it.domain }.eachCount() }
    val chips = remember(domainChips, unreadThreads, unreadByDomain) {
        buildList {
            add(Chip(CHIP_ALL, "All", unreadByDomain.values.sum()))
            add(Chip(DOMAIN_CHAT, "Chat", unreadByDomain[DOMAIN_CHAT] ?: 0))
            domainChips.forEach { add(Chip(it, it.replaceFirstChar { c -> c.uppercase() }, unreadByDomain[it] ?: 0)) }
            if (threads.isNotEmpty()) add(Chip(CHIP_THREADS, "Threads", unreadThreads))
        }
    }
    var chipKey by rememberSaveable { mutableStateOf(CHIP_ALL) }
    val selected = if (chips.any { it.key == chipKey }) chipKey else CHIP_ALL

    // The rows to render for the selected chip, newest first.
    val rows: List<FeedRow> = remember(selected, chatItems, notices) {
        when (selected) {
            DOMAIN_CHAT -> (chatItems.map { FeedRow.Chat(it) } +
                notices.filter { it.domain == DOMAIN_CHAT }.map { FeedRow.Notice(it) }).sortedByDescending { it.ts }
            CHIP_ALL -> (chatItems.map { FeedRow.Chat(it) } + notices.map { FeedRow.Notice(it) }).sortedByDescending { it.ts }
            CHIP_THREADS -> emptyList()
            else -> notices.filter { it.domain == selected }.map { FeedRow.Notice(it) }
        }
    }

    var refreshing by remember { mutableStateOf(cached == null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var retryCount by remember { mutableStateOf(0) }
    LaunchedEffect(retryCount) {
        refreshing = true
        error = null
        runCatching { repo.fetchActivityFeed() }.onFailure { error = it }
        refreshing = false
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            chips.forEach { c ->
                FilterChip(
                    selected = c.key == selected,
                    onClick = { chipKey = c.key },
                    label = { Text(if (c.unread > 0) "${c.label} · ${c.unread}" else c.label) },
                )
            }
        }

        if (selected == CHIP_THREADS) {
            if (unreadThreads > 0) {
                TextButton(
                    onClick = { repo.markThreadsRead(threads.filter { it.unread > 0 }.map { it.whom to it.parentPostId }) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) { Text("Mark all read") }
            }
            io.nisfeb.talon.ui.FollowedThreadsList(
                rows = threads,
                contactMap = contactMap,
                showChat = true,
                onOpen = { row -> onOpenReply(row.whom, row.parentPostId, row.lastReplyId ?: row.parentPostId) },
                modifier = Modifier.fillMaxSize(),
            )
            return@Column
        }

        // Mark-all-read for the notices in view (chat %activity has no client
        // read state, so this acts on notices: a domain chip clears its own,
        // All and Chat clear every notice).
        val unreadHere = rows.count { it is FeedRow.Notice && !it.n.read }
        if (unreadHere > 0) {
            TextButton(
                onClick = {
                    val domain = if (selected == CHIP_ALL || selected == DOMAIN_CHAT) null else selected
                    scope.launch { noticeDao.markAllRead(domain) }
                },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) { Text("Mark all read") }
        }

        if (refreshing && cached != null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        when {
            cached == null && refreshing -> Row(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }

            cached == null && error != null && rows.isEmpty() -> Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                io.nisfeb.talon.ui.ProblemLine(io.nisfeb.talon.util.problemOf("Couldn't load activity", error!!))
                TextButton(onClick = { retryCount++ }) { Text("Try again") }
            }

            rows.isEmpty() -> Text(
                when (selected) {
                    CHIP_ALL -> "No activity yet. Mentions, replies and updates will show up here."
                    DOMAIN_CHAT -> "No chat activity yet. Mentions and replies to your posts will show up here."
                    else -> "Nothing here yet."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                itemsIndexed(
                    items = rows,
                    key = { i, r -> "$i:${r.key}" },
                ) { _, r ->
                    when (r) {
                        is FeedRow.Chat -> ActivityRow(r.item, contactMap) {
                            val w = r.item.whom ?: return@ActivityRow
                            val parent = r.item.parentPostId
                            val post = r.item.postId
                            if (parent != null && post != null) onOpenReply(w, parent, post)
                            else if (post != null) onOpenPost(w, post)
                            else onOpenConversation(w)
                        }
                        is FeedRow.Notice -> NoticeRow(r.n) {
                            if (!r.n.read) scope.launch { noticeDao.markRead(r.n.id) }
                            onOpenNotice(r.n.target)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

private const val CHIP_ALL = "all"
private const val CHIP_THREADS = "threads"
private const val DOMAIN_CHAT = "chat"

private data class Chip(val key: String, val label: String, val unread: Int)

private sealed interface FeedRow {
    val ts: Long
    val key: String

    data class Chat(val item: TlonChatRepo.ActivityFeedItem) : FeedRow {
        override val ts get() = item.sentMs
        override val key get() = "c:${item.whom}:${item.sentMs}:${item.kind}"
    }

    data class Notice(val n: NoticeEntity) : FeedRow {
        override val ts get() = n.createdMs
        override val key get() = "n:${n.id}"
    }
}

@Composable
private fun NoticeRow(n: NoticeEntity, onClick: () -> Unit) {
    val timestamp = remember(n.createdMs) { if (n.createdMs > 0) formatMonthDayClock(n.createdMs) else "" }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(label = n.title, url = null, size = 40.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // A read notice dims; an unread one keeps full-weight title.
            val titleColor = if (n.read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            Text(
                n.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (n.read) FontWeight.Normal else FontWeight.Medium,
                ),
                color = titleColor,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (n.body.isNotBlank()) {
                Text(n.body, style = MaterialTheme.typography.bodyMedium, maxLines = 3, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (timestamp.isNotEmpty()) {
                Text(timestamp, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            StoryCache.textFor("activity:${it.hashCode()}", it)
                .replace('\n', ' ')
                .take(200)
        }
    }
    val timestamp = remember(item.sentMs) {
        if (item.sentMs > 0) formatMonthDayClock(item.sentMs) else ""
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
            val place = item.whom?.takeIf { it.contains('/') }
                ?.let { contactMap.conversationLabel(it) } ?: item.title
            val header = buildString {
                append(authorLabel ?: item.author ?: "someone")
                append(" · ")
                append(item.kind)
                append(" · ")
                append(place)
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
