package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageMediaEntity
import io.nisfeb.talon.ui.LinkPreviewCard
import io.nisfeb.talon.ui.LocalInlineMediaPlayer
import io.nisfeb.talon.ui.MediaKind
import io.nisfeb.talon.ui.combinedClickableWithSecondary
import io.nisfeb.talon.urbit.MediaCategory
import io.nisfeb.talon.urbit.TlonChatRepo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Drilldown list for one [MediaCategory] in a single chat.
 *
 * Photo / Gif categories render as a square thumbnail grid (Telegram-
 * style). Tapping a thumbnail opens [ImageViewerScreen] in list mode
 * via [onOpenImageList], so the viewer's prev/next + arrow-key
 * navigation steps through every image in the category.
 *
 * Audio / Voice categories render rows with the inline-media player
 * the chat surface already uses ([LocalInlineMediaPlayer]). Android
 * binds it to ExoPlayer-backed playback; desktop falls back to a
 * tap-to-open URL row until a desktop media stack is wired.
 *
 * Other categories (Video / File / Link) render as text rows; tapping
 * opens the URL via the system handler.
 */
@Composable
fun MediaListPane(
    db: AppDatabase,
    repo: TlonChatRepo,
    http: okhttp3.OkHttpClient,
    whom: String,
    category: MediaCategory,
    onOpenImageList: (urls: List<String>, initialIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uri = LocalUriHandler.current
    val items by remember(whom, category) {
        db.messageMedia().streamCategory(
            whom = whom,
            category = category.name,
            limit = 500, // first page; pagination can come later if needed
            offset = 0,
        )
    }.collectAsState(initial = emptyList())

    if (items.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            Text("Nothing yet", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    when (category) {
        MediaCategory.Photo, MediaCategory.Gif -> PhotoGrid(
            items = items,
            onOpen = { index ->
                onOpenImageList(items.map { it.url }, index)
            },
            modifier = modifier,
        )
        MediaCategory.Audio, MediaCategory.Voice -> AudioList(
            items = items,
            db = db,
            repo = repo,
            modifier = modifier,
        )
        MediaCategory.Link -> LinkList(
            items = items,
            db = db,
            repo = repo,
            http = http,
            onOpen = { item -> uri.openUri(item.url) },
            modifier = modifier,
        )
        // Video / File: simple text rows with metadata. No preview
        // card available, but the posted-at + author footer matches
        // what the audio + link rows show.
        else -> SimpleList(
            items = items,
            db = db,
            repo = repo,
            onOpen = { item -> uri.openUri(item.url) },
            modifier = modifier,
        )
    }
}

@Composable
private fun PhotoGrid(
    items: List<MessageMediaEntity>,
    onOpen: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Adaptive cells so we get more columns on wider windows (right
    // pane on desktop) and fewer on phones. 96dp gives ~3 columns at
    // 360dp drilldown width, ~4 at 480dp, etc.
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 96.dp),
        modifier = modifier.fillMaxWidth().padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(items, key = { _, item -> "${item.messageId}|${item.url}" }) { i, item ->
            AsyncImage(
                model = item.url,
                contentDescription = item.displayText,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clickable { onOpen(i) },
            )
        }
    }
}

@Composable
private fun AudioList(
    items: List<MessageMediaEntity>,
    db: AppDatabase,
    repo: TlonChatRepo,
    modifier: Modifier = Modifier,
) {
    val inlinePlayer = LocalInlineMediaPlayer.current
    val uri = LocalUriHandler.current
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(items, key = { "${it.messageId}|${it.url}" }) { item ->
            // tap on this row doesn't navigate — the inline player
            // claims the touch surface for play/pause/seek. Long-press
            // (or right-click on desktop) still opens the bookmark
            // menu via MediaRowMenuWrapper.
            MediaRowMenuWrapper(
                item = item,
                db = db,
                repo = repo,
                onTap = null,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        item.displayText ?: item.url,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                    )
                    AuthorAndTimestamp(
                        author = item.author,
                        sentMs = item.sentMs,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (inlinePlayer != null) {
                        inlinePlayer(item.url, MediaKind.AUDIO)
                    } else {
                        // No platform media stack wired (desktop today) —
                        // surface a tap-to-open-in-browser affordance so
                        // the row isn't a dead end. Android binds the
                        // composition local to ExoPlayer; this branch
                        // only fires on desktop / tests.
                        Text(
                            "Open in browser",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { uri.openUri(item.url) },
                        )
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun LinkList(
    items: List<MessageMediaEntity>,
    db: AppDatabase,
    repo: TlonChatRepo,
    http: okhttp3.OkHttpClient,
    onOpen: (MessageMediaEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(items, key = { "${it.messageId}|${it.url}" }) { item ->
            MediaRowMenuWrapper(
                item = item,
                db = db,
                repo = repo,
                onTap = { onOpen(item) },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    // OpenGraph preview card — same component used
                    // inline in chat bodies, so cache hits across the
                    // two surfaces. Renders nothing when the URL has
                    // no OG metadata, so bare links collapse cleanly
                    // to the text-only path below.
                    LinkPreviewCard(
                        url = item.url,
                        http = http,
                        onOpen = { onOpen(item) },
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        item.displayText ?: item.url,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                    )
                    AuthorAndTimestamp(
                        author = item.author,
                        sentMs = item.sentMs,
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun SimpleList(
    items: List<MessageMediaEntity>,
    db: AppDatabase,
    repo: TlonChatRepo,
    onOpen: (MessageMediaEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(items, key = { "${it.messageId}|${it.url}" }) { item ->
            MediaRowMenuWrapper(
                item = item,
                db = db,
                repo = repo,
                onTap = { onOpen(item) },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        item.displayText ?: item.url,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                    )
                    AuthorAndTimestamp(
                        author = item.author,
                        sentMs = item.sentMs,
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

/**
 * Wrap a media-list row body with tap + long-press / right-click
 * affordances. Tap (when [onTap] is non-null) opens the URL via the
 * caller's handler. Long-press / right-click pops a [DropdownMenu]
 * with bookmark / unbookmark actions; uses
 * [combinedClickableWithSecondary] so desktop's right-click triggers
 * the same path as mobile's long-press.
 *
 * Bookmarks persist via [TlonChatRepo.settingsSync.addBookmark] /
 * `removeBookmark` — same path the chat-message action sheet uses,
 * so the entry is synced across devices like the rest of the
 * %settings bookmark bucket.
 */
@Composable
private fun MediaRowMenuWrapper(
    item: MessageMediaEntity,
    db: AppDatabase,
    repo: TlonChatRepo,
    onTap: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var menuOpen by remember(item.messageId, item.url) { mutableStateOf(false) }
    val isBookmarked by remember(item.whom, item.messageId) {
        db.bookmarks().isBookmarked(item.whom, item.messageId)
    }.collectAsState(initial = false)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickableWithSecondary(
                onClick = onTap ?: {},
                onLongClick = { menuOpen = true },
            ),
    ) {
        content()
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            DropdownMenuItem(
                text = {
                    Text(if (isBookmarked) "Remove bookmark" else "Bookmark")
                },
                onClick = {
                    menuOpen = false
                    val sync = repo.settingsSync ?: return@DropdownMenuItem
                    scope.launch {
                        runCatching {
                            if (isBookmarked) {
                                sync.removeBookmark(item.whom, item.messageId)
                            } else {
                                sync.addBookmark(
                                    item.whom,
                                    item.messageId,
                                    System.currentTimeMillis(),
                                )
                            }
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun AuthorAndTimestamp(author: String, sentMs: Long) {
    val timestamp = remember(sentMs) {
        if (sentMs > 0) formatPostedAt(sentMs) else null
    }
    Text(
        if (timestamp != null) "$author · $timestamp" else author,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val POSTED_TIME_TODAY: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
        .withZone(ZoneId.systemDefault())
private val POSTED_DATE_OLD: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
        .withZone(ZoneId.systemDefault())

/** Match DmListScreen's relative format: "HH:mm" if today, "MMM d"
 *  otherwise. Keeps timestamps short (info pane is narrow) without
 *  being ambiguous for older content. */
private fun formatPostedAt(ms: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - ms
    val instant = Instant.ofEpochMilli(ms)
    return when {
        diff < 24 * 3600_000L -> POSTED_TIME_TODAY.format(instant)
        else -> POSTED_DATE_OLD.format(instant)
    }
}
