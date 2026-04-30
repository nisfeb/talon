// Diverges from production: production reads OkHttpClient via
// TalonApplication.http through LocalContext. commonMain takes the
// client as an explicit parameter so the screen can pass its own.
// Keep in sync with production until app/ is removed in Stage F.
package io.nisfeb.talon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.nisfeb.talon.urbit.LinkPreviewCache

/**
 * Card attached to a message showing the first URL's OpenGraph metadata.
 * Renders nothing while loading and nothing when the URL has no OG tags,
 * so plain-link messages stay visually clean.
 */
@Composable
fun LinkPreviewCard(
    url: String,
    http: okhttp3.OkHttpClient,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var preview by remember(url) { mutableStateOf<LinkPreviewCache.Preview?>(null) }

    LaunchedEffect(url) {
        preview = LinkPreviewCache.await(http, url)
    }

    val p = preview ?: return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 360.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onOpen(p.url) }
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (!p.imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = p.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .widthIn(max = 96.dp)
                    .height(72.dp)
                    .clip(RoundedCornerShape(6.dp)),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                p.domain,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!p.title.isNullOrBlank()) {
                Text(
                    p.title,
                    style = MaterialTheme.typography.bodyMedium
                        .copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                )
            }
            if (!p.description.isNullOrBlank()) {
                Text(
                    p.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

/**
 * Scan a list of StoryParts for the first URL annotation. Returns null
 * if none found — skips the preview card entirely in that case.
 */
fun firstLinkUrl(parts: List<io.nisfeb.talon.urbit.StoryPart>): String? {
    for (p in parts) {
        if (p is io.nisfeb.talon.urbit.StoryPart.Text) {
            val ann = p.text.getStringAnnotations(
                tag = io.nisfeb.talon.urbit.URL_TAG,
                start = 0,
                end = p.text.length,
            ).firstOrNull()
            if (ann != null) return ann.item
        }
    }
    return null
}
