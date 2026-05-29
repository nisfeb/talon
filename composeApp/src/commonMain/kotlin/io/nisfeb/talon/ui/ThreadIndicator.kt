package io.nisfeb.talon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Thread-reply indicator rendered under a message that has at least
 * one reply. Replaces the previous "💬 N" chip that read as just
 * another reaction. Shows an avatar of the most recent replier, the
 * reply count, and a short relative timestamp ("3m ago") so the user
 * can tell whether a thread is hot or stale without opening it.
 *
 * Tap navigates into the thread.
 *
 * The contract is permissive about missing data: an empty
 * [lastAuthor] (defensive default on [io.nisfeb.talon.data.ReplyCount])
 * just skips the avatar.
 */
@Composable
fun ThreadIndicator(
    count: Int,
    lastSentMs: Long,
    lastAuthor: String,
    contactMap: ContactMap,
    nowMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** True when this thread has unseen replies. Tints the pill with
     *  the accent (primaryContainer surface + onPrimaryContainer text)
     *  so it matches the same accent the home-list unread badges and
     *  the in-chat "New" divider use. Driven by ThreadUnreadEntity. */
    hasUnread: Boolean = false,
) {
    if (count <= 0) return
    val bg = if (hasUnread) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (hasUnread) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        if (lastAuthor.isNotBlank()) {
            Avatar(
                label = contactMap.displayName(lastAuthor),
                url = contactMap.avatar(lastAuthor),
                colorHex = contactMap.shipColor(lastAuthor),
                size = 20.dp,
            )
        }
        Text(
            text = if (count == 1) "1 reply" else "$count replies",
            style = MaterialTheme.typography.labelMedium,
            color = fg,
        )
        if (lastSentMs > 0L) {
            Spacer(Modifier.width(2.dp))
            Text("·", style = MaterialTheme.typography.labelMedium, color = fg)
            Text(
                text = shortRelativeTime(thenMs = lastSentMs, nowMs = nowMs),
                style = MaterialTheme.typography.labelMedium,
                color = fg,
            )
        }
    }
}
