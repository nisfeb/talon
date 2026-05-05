package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Desktop / tablet-landscape host. Below [ExpandedThreshold] this is a
 * passthrough: we render only [detail] when set, otherwise [list] —
 * matching the stack-style mobile flow Phase 1 already established.
 *
 * At/above the threshold we add a 64dp vertical icon rail on the far
 * left, then defer to [ChatPaneScaffold] for the list / detail split
 * with its drag handle. A reserved [rightSidebar] slot is null in
 * Phase 2; Phase 3 will fill it with thread / files / links / pinned-
 * post panels.
 *
 * The rail is rendered ONLY inside the expanded branch — never as a
 * sibling of [ChatPaneScaffold] — so a compact-mode resize doesn't
 * leave a dangling 64dp gutter on phones / narrow desktop windows.
 */
@Composable
fun DesktopShell(
    activeRailTab: RailTab,
    onSelectRailTab: (RailTab) -> Unit,
    list: @Composable () -> Unit,
    detail: (@Composable () -> Unit)?,
    listFraction: Float,
    onListFractionChange: (Float) -> Unit,
    rightSidebar: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val expanded = maxWidth >= ExpandedThreshold
        if (!expanded) {
            // Compact: rail/sidebar collapsed; identical to Phase 1.
            if (detail != null) detail() else list()
            return@BoxWithConstraints
        }
        Row(modifier = Modifier.fillMaxSize()) {
            DesktopRail(
                activeTab = activeRailTab,
                onSelect = onSelectRailTab,
            )
            Box(modifier = Modifier.fillMaxHeight().fillMaxSize()) {
                ChatPaneScaffold(
                    list = list,
                    detail = detail,
                    listFraction = listFraction,
                    onListFractionChange = onListFractionChange,
                )
            }
            // Phase 3: rightSidebar?.invoke() once we wire threads /
            // files / links here. Today rightSidebar stays null and
            // we don't render an empty fourth column.
        }
    }
}

@Composable
private fun DesktopRail(
    activeTab: RailTab,
    onSelect: (RailTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxHeight().width(RAIL_WIDTH),
    ) {
        Column(
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxHeight().padding(vertical = 8.dp),
        ) {
            for (tab in RailTab.entries) {
                RailIconButton(
                    tab = tab,
                    isSelected = tab == activeTab,
                    onClick = { onSelect(tab) },
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun RailIconButton(
    tab: RailTab,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (isSelected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant
    IconButton(onClick = onClick) {
        Icon(
            imageVector = railIcon(tab),
            contentDescription = railLabel(tab),
            tint = tint,
        )
    }
}

private fun railIcon(tab: RailTab): ImageVector = when (tab) {
    // All four icons live in material-icons-core (always shipped) so
    // they're outside the slim-task's keep-list and don't risk an R8
    // strip on Android. (Android phones won't render the rail anyway,
    // but the tablet build does.)
    RailTab.Chats -> Icons.Filled.Home
    RailTab.Statuses -> Icons.Filled.Notifications
    RailTab.Bookmarks -> Icons.Filled.Star
    RailTab.Activity -> Icons.Filled.Notifications  // placeholder — pick distinct in 3.2
}

private fun railLabel(tab: RailTab): String = when (tab) {
    RailTab.Chats -> "Chats"
    RailTab.Statuses -> "Statuses"
    RailTab.Bookmarks -> "Bookmarks"
    RailTab.Activity -> "Activity"
}

private val RAIL_WIDTH = 64.dp
