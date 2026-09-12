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
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.ui.graphics.vector.rememberVectorPainter
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
import androidx.compose.ui.draw.alpha
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
    http: io.ktor.client.HttpClient,
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
    var loading by remember(whom) { mutableStateOf(true) }
    // Older pages: the newest 30 come from the refresh below; the rest
    // load as the grid nears its end, the way the chat list does.
    val gridState = androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState()
    var paginating by remember(whom) { mutableStateOf(false) }
    var exhausted by remember(whom) { mutableStateOf(false) }
    LaunchedEffect(whom, gridState) {
        androidx.compose.runtime.snapshotFlow {
            gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to gridState.layoutInfo.totalItemsCount
        }.collect { (last, total) ->
            if (last == null || total == 0 || last < total - 4 || paginating || exhausted) return@collect
            paginating = true
            exhausted = !runCatching { repo.loadOlder(whom) }.getOrDefault(false)
            paginating = false
        }
    }
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
            io.nisfeb.talon.ui.NavIcon(onBack = onBack)
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
                state = gridState,
                columns = StaggeredGridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalItemSpacing = 8.dp,
            ) {
                items(items = posts, key = { it.id }) { post ->
                    GalleryTile(
                        http = http,
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
    http: io.ktor.client.HttpClient,
    post: MessageEntity,
    contactMap: ContactMap,
    onClick: () -> Unit,
) {
    val parts = remember(post.id, post.contentJson) {
        StoryCache.partsFor(post.id, post.contentJson)
    }
    // A link post whose meta is empty (Talon's own until now, or a
    // site that blocked the poster) and a legacy inline link carry no
    // preview of their own: fetch one here, like chat's preview card.
    val previewUrl = remember(parts) { io.nisfeb.talon.ui.galleryPreviewUrl(parts) }
    var fetched by remember(previewUrl) {
        mutableStateOf<io.nisfeb.talon.urbit.LinkPreviewCache.Preview?>(null)
    }
    LaunchedEffect(previewUrl) {
        if (previewUrl != null) {
            fetched = io.nisfeb.talon.urbit.LinkPreviewCache.await(http, previewUrl)
        }
    }
    val primary = parts.firstOrNull { it is StoryPart.Image }
        ?: fetched?.let {
            StoryPart.LinkPreview(it.url, it.title, it.description, it.imageUrl, it.domain)
        }
        ?: parts.firstOrNull { it is StoryPart.LinkPreview }
        ?: parts.firstOrNull { it is StoryPart.Text }
        ?: parts.firstOrNull { it is StoryPart.Citation }
    // An optimistic twin the ship has not echoed, or one it refused:
    // same dimming and marker as a chat row, so a post that never
    // landed does not pass for a real one.
    val pending = post.status == "pending" || post.id.startsWith("local_")
    val failed = post.status == "failed"

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (pending || failed) 0.55f else 1f)
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
                    // A dead image used to leave a blank card the height
                    // of its aspect ratio; show the same broken glyph
                    // the post view does.
                    AsyncImage(
                        model = primary.src,
                        contentDescription = primary.alt,
                        error = rememberVectorPainter(Icons.Filled.BrokenImage),
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
                            error = rememberVectorPainter(Icons.Filled.BrokenImage),
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
                // A share-to-gallery post: a reference to another post,
                // channel or group and nothing else.
                is StoryPart.Citation -> {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            primary.label,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 4,
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
                when {
                    failed -> "Not posted"
                    pending -> "Sending…"
                    else -> contactMap.nickname(post.author) ?: post.author
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}
