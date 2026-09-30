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
 * preview card: the card kept from last time at once, then the page's
 * own ([UrbUnfurlCache.cards]); nothing when there's no viewer ship or
 * lattice isn't serving it. Provided at the app root over http + the
 * active ship; default returns nothing so previews/tests don't crash.
 */
val LocalUrbFetcher =
    staticCompositionLocalOf<((String) -> kotlinx.coroutines.flow.Flow<UrbUnfurlCache.Unfurl>)?> { null }

/** The active ship's HTTP base, for features that build ship URLs
 *  (e.g. publishing to Lattice). Null when signed out. */
val LocalShipUrl = staticCompositionLocalOf<String?> { null }

/** The active ship's session cookie ("name=value") — the shared http
 *  client has no cookie store, so authenticated ship requests set it
 *  by hand. Null when signed out. */
val LocalShipCookie = staticCompositionLocalOf<String?> { null }

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
    LaunchedEffect(urbUrl) { fetcher(urbUrl).collect { unfurl = it } }
    val u = unfurl ?: return
    // "urb://~ship" caption, like a domain on a link card.
    val host = "urb://" + urbUrl.removePrefix("urb://").substringBefore('/')
    InlineLinkCard(host, u.title, u.snippet, onClick = { onOpen(urbUrl) }, modifier = modifier)
}

/**
 * The card under a message that links a furum board or post ([link], a
 * page URL or the f/ shorthand), from what the reader's own furum knows
 * of it ([FurumPreview]). Nothing while it loads, or where the ship has
 * no furum or no card for it. Tapping opens it as the link does.
 */
@Composable
fun FurumCard(
    link: String,
    http: io.ktor.client.HttpClient,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ref = remember(link) { io.nisfeb.talon.urbit.FurumLink.parse(link) } ?: return
    val shipUrl = LocalShipUrl.current ?: return
    val cookie = LocalShipCookie.current ?: return
    var card by remember(ref) { mutableStateOf<io.nisfeb.talon.urbit.FurumPreview.Card?>(null) }
    LaunchedEffect(ref, shipUrl) { card = io.nisfeb.talon.urbit.FurumPreview.await(http, shipUrl, cookie, ref) }
    val c = card ?: return
    InlineLinkCard(c.caption, c.title, c.snippet, onClick = { onOpen(link) }, modifier = modifier)
}

/** A link card: a caption like a domain, a title, and a line from what it links. */
@Composable
private fun InlineLinkCard(
    caption: String,
    title: String?,
    snippet: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 360.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!title.isNullOrBlank()) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!snippet.isNullOrBlank()) {
            Text(
                snippet,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
