package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageMediaEntity
import io.nisfeb.talon.urbit.MediaCategory

/**
 * Drilldown list for one [MediaCategory] in a single chat. Tap on an
 * item routes to the right behaviour for the bucket: image viewer for
 * Photo/Gif, system URI handler for everything else (the inline media
 * player handles Audio/Video playback inline elsewhere; opening the
 * raw URL in a browser is a deliberate choice for the drilldown).
 */
@Composable
fun MediaListPane(
    db: AppDatabase,
    whom: String,
    category: MediaCategory,
    onOpenImage: (url: String) -> Unit,
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

    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(items, key = { "${it.messageId}|${it.url}" }) { item ->
            MediaRow(
                item = item,
                onClick = {
                    when (category) {
                        MediaCategory.Photo, MediaCategory.Gif -> onOpenImage(item.url)
                        else -> uri.openUri(item.url)
                    }
                },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun MediaRow(
    item: MessageMediaEntity,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
}
