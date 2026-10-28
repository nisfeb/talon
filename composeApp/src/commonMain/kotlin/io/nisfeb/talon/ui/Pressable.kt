package io.nisfeb.talon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Something to press, drawn as one: a slightly raised, rounded surface
 * under it, tinted when it is the one chosen. Rows of plain text and a
 * count with an emoji under it read as labels, and a user said of the
 * group Info pane that you'd have no idea at first sight they could be
 * pressed. The raised colour is the theme's (a custom theme's "raised").
 */
@Composable
fun Modifier.pressable(
    enabled: Boolean = true,
    selected: Boolean = false,
    shape: Shape = RoundedCornerShape(8.dp),
    onClick: () -> Unit,
): Modifier {
    val colors = MaterialTheme.colorScheme
    // Not surfaceVariant: in the light palette that is 2% off the page,
    // which still read as nothing to press.
    val fill = if (selected) colors.secondaryContainer else colors.surfaceContainerHighest
    return clip(shape)
        .background(if (enabled) fill else fill.copy(alpha = 0.5f))
        .clickable(enabled = enabled, onClick = onClick)
}
