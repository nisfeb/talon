package io.nisfeb.talon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.urbit.UrbUnfurlCache

/**
 * Resolves a urb:// address to its title + snippet for the inline
 * preview card, or null when there's no viewer ship / lattice isn't
 * serving it. Provided at the app root over http + the active ship;
 * default returns nothing so previews/tests don't crash.
 */
val LocalUrbFetcher =
    staticCompositionLocalOf<(suspend (String) -> UrbUnfurlCache.Unfurl?)?> { null }

/** The active ship's HTTP base, for features that build ship URLs
 *  (e.g. publishing to Lattice). Null when signed out. */
val LocalShipUrl = staticCompositionLocalOf<String?> { null }

/**
 * Inline preview for a urb:// link in a message — the lattice
 * referent's title and first line, like an OpenGraph card. Renders
 * nothing while loading or when there's no preview, so a bare urb://
 * link stays clean. Tapping opens the full viewer via [onOpen].
 */
@Composable
fun UrbUnfurlCard(
    urbUrl: String,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fetcher = LocalUrbFetcher.current ?: return
    var unfurl by remember(urbUrl) { mutableStateOf<UrbUnfurlCache.Unfurl?>(null) }
    LaunchedEffect(urbUrl) { unfurl = fetcher(urbUrl) }
    val u = unfurl ?: return
    // "urb://~ship" caption, like a domain on a link card.
    val host = "urb://" + urbUrl.removePrefix("urb://").substringBefore('/')
    Column(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 360.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onOpen(urbUrl) }
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            host,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!u.title.isNullOrBlank()) {
            Text(
                u.title!!,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!u.snippet.isNullOrBlank()) {
            Text(
                u.snippet!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
