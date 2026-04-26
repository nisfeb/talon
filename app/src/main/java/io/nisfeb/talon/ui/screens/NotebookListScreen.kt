package io.nisfeb.talon.ui.screens

import android.content.Intent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.nisfeb.talon.TalonApplication
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.contactMapFlow
import io.nisfeb.talon.urbit.StoryCache
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
    whom: String,
    onBack: () -> Unit,
    onOpenPost: (postId: String) -> Unit,
    onCompose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as TalonApplication
    val db = app.db
    val repo = app.repo

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

    var refreshing by remember(whom) { mutableStateOf(false) }
    // Loading state controls only the *first-paint* spinner shown when
    // there's nothing cached yet. Once cached posts arrive, the
    // refreshing flag drives a thin progress strip instead so the
    // grid stays interactive.
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
        val mountMs = android.os.SystemClock.elapsedRealtime()
        android.util.Log.i("NotebookList", "mount whom=$whom posts=${posts.size}")
        refreshing = true
        val started = android.os.SystemClock.elapsedRealtime()
        runCatching { repo.refreshConversation(whom, count = 30) }
            .onSuccess {
                android.util.Log.i(
                    "NotebookList",
                    "refresh $whom done in ${android.os.SystemClock.elapsedRealtime() - started}ms posts=${posts.size}",
                )
            }
            .onFailure {
                android.util.Log.w(
                    "NotebookList",
                    "refresh $whom failed after ${android.os.SystemClock.elapsedRealtime() - started}ms: ${it.message}",
                )
            }
        loading = false
        refreshing = false
        android.util.Log.i(
            "NotebookList",
            "post-refresh whom=$whom posts=${posts.size} elapsed=${android.os.SystemClock.elapsedRealtime() - mountMs}ms",
        )
    }

    // Trace each fresh emission of the post list to confirm Room is
    // actually delivering cached rows on essays-open.
    LaunchedEffect(whom) {
        val mountMs = android.os.SystemClock.elapsedRealtime()
        var firstEmit = true
        androidx.compose.runtime.snapshotFlow { posts.size }.collect { n ->
            if (firstEmit) {
                android.util.Log.i(
                    "NotebookList",
                    "first posts emit whom=$whom size=$n elapsed=${android.os.SystemClock.elapsedRealtime() - mountMs}ms",
                )
                firstEmit = false
            } else {
                android.util.Log.v(
                    "NotebookList",
                    "posts update whom=$whom size=$n elapsed=${android.os.SystemClock.elapsedRealtime() - mountMs}ms",
                )
            }
        }
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
        // Subtle network-activity strip while a refresh scry is in
        // flight, but only once we already have cached posts to show
        // — the "no posts yet" first-open path uses the centered
        // CircularProgressIndicator instead.
        if (refreshing && posts.isNotEmpty()) {
            androidx.compose.material3.LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
            )
        }
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

// Small helper so callers can share a ShareIntent if we ever add "share post".
@Suppress("unused")
private fun sharePost(intent: Intent) {}
