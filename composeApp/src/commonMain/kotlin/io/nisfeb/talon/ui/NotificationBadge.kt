package io.nisfeb.talon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Every notification bubble in the app, with the count written in it,
 * "99+" past 99: in the theme's colour, the primary as the unread pills
 * always were, unless the theme sets its own (sneagan, 2026-11-05: not
 * the redesign's red, or the theme overrides it). See [LocalBadgeColor].
 */
val LocalBadgeColor = androidx.compose.runtime.staticCompositionLocalOf<Color?> { null }

/** The badge's fill: the theme's own, else its primary. */
@Composable
fun badgeFill(): Color = LocalBadgeColor.current ?: MaterialTheme.colorScheme.primary

/** The text a bubble shows for [count]: the number, or "99+" past 99. */
fun notificationBadgeLabel(count: Int): String = if (count > 99) "99+" else count.toString()

/** A red bubble holding [count]. Draws nothing for a count under 1. */
@Composable
fun NotificationBadge(count: Int, modifier: Modifier = Modifier) {
    if (count < 1) return
    val label = notificationBadgeLabel(count)
    val fill = badgeFill()
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(fill)
            .padding(horizontal = 6.dp)
            // The count once: the text inside is not read again after it.
            .clearAndSetSemantics { contentDescription = "$label unread" },
    ) {
        Text(
            label,
            // The primary's own ink, or black or white on a theme's colour.
            color = if (LocalBadgeColor.current == null) MaterialTheme.colorScheme.onPrimary
                else readableInk(fill, fill, Color.White),
            fontSize = 11.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/**
 * The red mark for a place that knows something is new but not how
 * many: the rail and menu items whose sources keep no count yet.
 */
@Composable
fun NotificationDot(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(badgeFill())
            .semantics { contentDescription = "Unread" },
    )
}
