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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Every notification bubble in the app: red, with the count written in
 * it, "99+" past 99. One look in light and dark, and in every theme, so
 * it never reads as the accent.
 */
val NotificationRed = Color(0xFFE5484D)

/** The text a bubble shows for [count]: the number, or "99+" past 99. */
fun notificationBadgeLabel(count: Int): String = if (count > 99) "99+" else count.toString()

/** A red bubble holding [count]. Draws nothing for a count under 1. */
@Composable
fun NotificationBadge(count: Int, modifier: Modifier = Modifier) {
    if (count < 1) return
    val label = notificationBadgeLabel(count)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(NotificationRed)
            .padding(horizontal = 6.dp)
            .semantics { contentDescription = "$label unread" },
    ) {
        Text(
            label,
            color = Color.White,
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
            .background(NotificationRed)
            .semantics { contentDescription = "Unread" },
    )
}
