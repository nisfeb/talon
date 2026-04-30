// Adapted: TalonApplication coupling replaced with constructor
// injection (db, repo) — matches the DmListScreen / DmChatScreen
// pattern. Keep in sync with production until app/ is removed in
// Stage F.
package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
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
import io.nisfeb.talon.urbit.StoryPart
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Staggered-grid view of a gallery (%heap) channel. Each tile shows
 * the single visual element of the post: an image, a link preview,
 * or a text snippet. Newest first.
 */
@Composable
fun GalleryGridScreen(
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

    // First-paint spinner only shown when there's nothing cached. Once
    // cached posts arrive the grid renders them — a background refresh
    // that fails (wedged network ⇒ 6s OkHttp cap) shouldn't leave a
    // spinner running on top of content.
    var loading by remember { mutableStateOf(true) }
    // Clear the badge instantly: zero out the home-snapshot row (so a
    // back-nav paints a fresh state immediately) and tell the repo the
    // chat is focused. setOpenChat fires markRead off-thread so the
    // refresh below doesn't gate it. Mirrors DmChatScreen — without
    // this, the badge lingered for the duration of the refresh scry.
    DisposableEffect(whom) {
        homeSnapshotZeroUnread(whom)
        repo.setOpenChat(whom)
        onDispose { repo.setOpenChat(null) }
    }
    LaunchedEffect(whom) {
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
                Icon(Icons.Filled.Add, contentDescription = "New post")
            }
        }
        HorizontalDivider()
        when {
            loading && posts.isEmpty() -> Row(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }

            posts.isEmpty() -> Text(
                "No posts yet — tap + to share something.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )

            else -> LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalItemSpacing = 8.dp,
            ) {
                items(items = posts, key = { it.id }) { post ->
                    GalleryTile(
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
private fun GalleryTile(
    post: MessageEntity,
    contactMap: ContactMap,
    onClick: () -> Unit,
) {
    val parts = remember(post.id, post.contentJson) {
        StoryCache.partsFor(post.id, post.contentJson)
    }
    val primary = parts.firstOrNull { it is StoryPart.Image }
        ?: parts.firstOrNull { it is StoryPart.LinkPreview }
        ?: parts.firstOrNull { it is StoryPart.Text }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Column {
            when (primary) {
                is StoryPart.Image -> {
                    val ratio = if (primary.width != null && primary.height != null &&
                        primary.width > 0 && primary.height > 0
                    ) primary.width.toFloat() / primary.height.toFloat()
                    else 1f
                    AsyncImage(
                        model = primary.src,
                        contentDescription = primary.alt,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(ratio.coerceIn(0.4f, 2.5f))
                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                    )
                }
                is StoryPart.LinkPreview -> {
                    primary.imageUrl?.let { url ->
                        AsyncImage(
                            model = url,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                        )
                    }
                    Column(Modifier.padding(10.dp)) {
                        primary.title?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                maxLines = 2,
                            )
                        }
                        primary.siteName?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
                is StoryPart.Text -> {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            primary.text.text.take(280),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 10,
                        )
                    }
                }
                else -> {
                    Text(
                        "(empty post)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            Text(
                contactMap.nickname(post.author) ?: post.author,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}
