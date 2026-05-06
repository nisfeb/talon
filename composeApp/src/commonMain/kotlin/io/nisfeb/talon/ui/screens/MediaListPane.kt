package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageMediaEntity
import io.nisfeb.talon.urbit.MediaCategory

/**
 * Drilldown list for one [MediaCategory] in a single chat.
 *
 * Photo / Gif categories render as a square thumbnail grid (Telegram-
 * style). Tapping a thumbnail opens [ImageViewerScreen] in list mode
 * via [onOpenImageList], so the viewer's prev/next + arrow-key
 * navigation steps through every image in the category.
 *
 * Other categories render as a list of `displayText / url / author`
 * rows; tapping a row opens the URL via the system handler.
 */
@Composable
fun MediaListPane(
    db: AppDatabase,
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
        else -> LinkList(
            items = items,
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
private fun LinkList(
    items: List<MessageMediaEntity>,
    onOpen: (MessageMediaEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(items, key = { "${it.messageId}|${it.url}" }) { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(item) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        item.displayText ?: item.url,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                    )
                    Text(
                        item.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
        }
    }
}
