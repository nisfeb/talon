package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Small low-alpha "⋯" icon button + anchored dropdown for message
 * actions. The dropdown opens adjacent to the ellipsis (handled by
 * [DropdownMenu] auto-positioning) rather than as a bottom sheet, so
 * the menu sits next to the message the user just acted on instead
 * of obscuring the whole bottom edge.
 *
 * [expanded] / [onExpandedChange] are lifted so the parent row can
 * also open the menu via right-click ([onSecondaryClick]); the
 * IconButton itself routes its tap through the same setter.
 *
 * [menuContent] is invoked inside the [DropdownMenu]'s body with a
 * `dismiss` callback so each action can close the menu after firing.
 */
@Composable
fun MessageActionsButton(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    menuContent: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    Box(modifier) {
        IconButton(
            onClick = { onExpandedChange(true) },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.MoreHoriz,
                contentDescription = "Message actions",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            // Keep the dropdown roughly bubble-width so the reaction
            // palette row fits without wrapping but the menu doesn't
            // dominate small windows.
            modifier = Modifier.widthIn(min = 280.dp, max = 360.dp),
        ) {
            menuContent { onExpandedChange(false) }
        }
    }
}
