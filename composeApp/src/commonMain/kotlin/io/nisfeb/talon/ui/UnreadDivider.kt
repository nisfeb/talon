package io.nisfeb.talon.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp

/** Fade duration once the dwell elapses, in ms. */
const val UNREAD_DIVIDER_FADE_MS = 3_000

/**
 * "New" divider rendered above the first unread row in a chat or
 * thread list. Color reads `colorScheme.primary` so the tint follows
 * the user's accent (brand amber by default, profile color or custom
 * hex when accent is on).
 *
 * [faded] drives the clear behavior: when the caller flips it true
 * (after the divider has been visible for its dwell), the content
 * fades to fully transparent over [UNREAD_DIVIDER_FADE_MS]. The Row's
 * HEIGHT is deliberately unchanged throughout — we fade alpha only and
 * never collapse the element, so nothing below it reflows and no tap
 * target slides under the user's finger / cursor mid-interaction. The
 * now-blank space self-heals on the next screen entry, when the anchor
 * re-seeds and the divider isn't emitted at all.
 */
@Composable
fun UnreadDividerRow(faded: Boolean = false) {
    val alpha by animateFloatAsState(
        targetValue = if (faded) 0f else 1f,
        animationSpec = tween(durationMillis = UNREAD_DIVIDER_FADE_MS),
        label = "unreadDividerFade",
    )
    val tint = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .alpha(alpha),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = tint)
        Text(
            "New",
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = tint)
    }
}
