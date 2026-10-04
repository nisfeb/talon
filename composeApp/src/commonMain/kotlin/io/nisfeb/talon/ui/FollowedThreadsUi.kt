package io.nisfeb.talon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.data.FollowedThreadRow
import io.nisfeb.talon.util.nowMs

/**
 * The threads the owner follows, each with its parent post, its newest
 * reply and how many are unread: the way back to a thread that scrolled
 * out of its chat. [showChat] names each one's chat, for the list across
 * every chat.
 */
@Composable
fun FollowedThreadsList(
    rows: List<FollowedThreadRow>,
    contactMap: ContactMap,
    showChat: Boolean,
    onOpen: (FollowedThreadRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (rows.isEmpty()) {
        Text(
            "No threads followed yet. Reply or react in a thread to follow it, or choose Follow thread on a post.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(24.dp),
        )
        return
    }
    val now = nowMs()
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(vertical = 4.dp)) {
        items(rows, key = { "${it.whom}#${it.parentPostId}" }) { row ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().clickable { onOpen(row) }.padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    if (showChat) {
                        Text(
                            contactMap.conversationLabel(row.whom),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        threadTitle(row, contactMap),
                        style = MaterialTheme.typography.bodyMedium.let { if (row.unread > 0) it.copy(fontWeight = FontWeight.SemiBold) else it },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        threadLine(row, contactMap, now),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (row.unread > 0) Badge { Text("${row.unread}") }
            }
        }
    }
}

/** The parent post's words, by its author; a placeholder while the post is not kept here. */
private fun threadTitle(row: FollowedThreadRow, contactMap: ContactMap): String {
    val content = row.parentContent ?: return "A post not loaded yet"
    val text = io.nisfeb.talon.urbit.StoryCache.textFor(row.parentPostId, content).replace('\n', ' ').trim()
    val by = row.parentAuthor?.let { contactMap.displayName(it) + ": " }.orEmpty()
    return by + text.ifBlank { "(attachment)" }
}

/** "3 replies · Nec · 5m ago" */
private fun threadLine(row: FollowedThreadRow, contactMap: ContactMap, now: Long): String = buildList {
    add(if (row.replyCount == 1) "1 reply" else "${row.replyCount} replies")
    row.lastReplier?.let { add(contactMap.displayName(it)) }
    row.lastReplyMs?.let { add(shortRelativeTime(thenMs = it, nowMs = now)) }
}.joinToString(" · ")

/** Above a chat: some of the threads the owner follows in it have new replies. */
@Composable
fun FollowedThreadsChip(count: Int, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Text(
            if (count == 1) "A thread you follow has new replies" else "$count threads you follow have new replies",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** One chat's followed threads, from its chip or its header. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowedThreadsSheet(
    rows: List<FollowedThreadRow>,
    contactMap: ContactMap,
    onOpen: (FollowedThreadRow) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Text(
            "Threads you follow here",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        FollowedThreadsList(rows, contactMap, showChat = false, onOpen = onOpen)
    }
}
