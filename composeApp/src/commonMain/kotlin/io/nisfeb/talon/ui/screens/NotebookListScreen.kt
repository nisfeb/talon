// TEMPORARY DUPLICATE of app/src/main/java/io/nisfeb/talon/ui/screens/NotebookListScreen.kt
// Adapted: TalonApplication / LocalContext-injected db+repo replaced with
// AppDatabase + TlonChatRepo Composable parameters (DmListScreen pattern).
// The unused `private fun sharePost(intent: Intent)` stub is dropped — its
// only call site was Android Intent, which has no commonMain analogue;
// see TODO(port-d5-followup): URL opener.
// Keep in sync with production until app/ is removed in Stage F.
package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.contactMapFlow
import io.nisfeb.talon.urbit.StoryCache
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lists notebook (%diary) entries in a channel. Newest first; tap a
 * card to open the full post.
 */
@Composable
fun NotebookListScreen(
    db: AppDatabase,
    repo: TlonChatRepo,
    whom: String,
    onBack: () -> Unit,
    onOpenPost: (postId: String) -> Unit,
    onCompose: () -> Unit,
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

    // distinctUntilChanged on the upstream so unrelated messages-table
    // writes don't re-emit; flowOn(Default) keeps the reverse off main.
    val posts by remember(whom) {
        db.messages().stream(whom)
            .distinctUntilChanged()
            .map { it.asReversed() }
            .flowOn(Dispatchers.Default)
    }.collectAsState(initial = emptyList())

    // First-paint spinner shown only when there's nothing cached yet.
    // Once cached posts arrive the LazyColumn just renders them — a
    // background refresh that fails (wedged network ⇒ 6s OkHttp cap)
    // shouldn't leave a spinner running on top of content.
    var loading by remember { mutableStateOf(true) }
    // Clear the badge instantly: zero out the home-snapshot row (so a
    // back-nav paints a fresh state immediately) and tell the repo the
    // chat is focused. setOpenChat fires markRead off-thread so the
    // refresh below doesn't gate it. Also activates the focus filter
    // that suppresses any /v4/activity bumps for this whom while the
    // user is here. Mirrors DmChatScreen — without this, the badge
    // lingered for the duration of the refresh scry.
    DisposableEffect(whom) {
        homeSnapshotZeroUnread(whom)
        repo.setOpenChat(whom)
        onDispose { repo.setOpenChat(null) }
    }
    LaunchedEffect(whom) {
        // Always scry newest on mount so we catch up on anything the
        // SSE stream missed (and so first-open isn't empty). The
        // subscription keeps us live after.
        runCatching { repo.refreshConversation(whom, count = 30) }
        loading = false
    }

    val title = remember(contactMap, whom) { contactMap.conversationLabel(whom) }

    Column(modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 4.dp).weight(1f),
                maxLines = 1,
            )
            IconButton(onClick = onCompose) {
                Icon(Icons.Filled.Edit, contentDescription = "New post")
            }
        }
        HorizontalDivider()
        when {
            loading && posts.isEmpty() -> Row(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }

            posts.isEmpty() -> Text(
                "No posts yet — tap the edit icon to write one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items = posts, key = { it.id }) { post ->
                    NotebookCard(
                        post = post,
                        contactMap = contactMap,
                        onClick = { onOpenPost(post.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NotebookCard(
    post: MessageEntity,
    contactMap: ContactMap,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Column {
            post.image?.takeIf { it.isNotBlank() }?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                )
            }
            Column(Modifier.padding(16.dp)) {
                val title = post.title?.takeIf { it.isNotBlank() } ?: "(untitled)"
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
                Spacer(Modifier.height(6.dp))
                val authorName = contactMap.nickname(post.author) ?: post.author
                Text(
                    "$authorName · ${formatDate(post.sentMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                val excerpt = remember(post.id, post.contentJson) {
                    plainTextExcerpt(post.id, post.contentJson, limit = 240)
                }
                if (excerpt.isNotBlank()) {
                    Text(
                        excerpt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 4,
                    )
                }
            }
        }
    }
}

private fun plainTextExcerpt(id: String, contentJson: String, limit: Int): String {
    val parts = StoryCache.partsFor(id, contentJson)
    val text = parts.joinToString(" ") { p ->
        when (p) {
            is io.nisfeb.talon.urbit.StoryPart.Text -> p.text.text
            else -> ""
        }
    }.replace(Regex("\\s+"), " ").trim()
    return if (text.length <= limit) text else text.take(limit - 1) + "…"
}

private val DATE_FMT = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
private fun formatDate(ms: Long): String = DATE_FMT.format(Date(ms))

// TODO(port-d5-followup): URL opener — production keeps a `sharePost(Intent)`
// stub so a future "share post" feature can wire through Android's share
// sheet. commonMain has no analogue yet.
