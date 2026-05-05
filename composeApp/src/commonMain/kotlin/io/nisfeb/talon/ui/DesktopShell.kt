package io.nisfeb.talon.ui

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
            // weight(1f) so the scaffold fills the remaining width AFTER
            // the rail's 64dp. fillMaxSize() here used to draw the
            // scaffold over the rail (Row siblings overlap when they
            // don't share width via weight) — symptom: the ship-switcher
            // drawer inside DmListScreen poked through where the rail
            // should be, and the rail icons only appeared once the
            // drawer was open and its panel happened to clip against
            // the list-pane bounds.
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                ChatPaneScaffold(
                    list = list,
                    detail = detail,
                    listFraction = listFraction,
                    onListFractionChange = onListFractionChange,
                )
            }
            // Right sidebar — Phase 3's thread / group-info / media-
            // drilldown surface. Fixed 360dp width when present; when
            // null we don't render an empty fourth column. The caller
            // (App.kt) only supplies a non-null lambda when there's
            // active content to show, so a no-content right pane never
            // wastes screen real estate. Phase 2 reserved this slot in
            // the API but didn't render it — Phase 3's App.kt wiring
            // sent content here that was silently dropped on the floor
            // until 0.10.0-rc4.
            if (rightSidebar != null) {
                androidx.compose.material3.VerticalDivider()
                Box(modifier = Modifier.width(RIGHT_SIDEBAR_WIDTH).fillMaxHeight()) {
                    rightSidebar()
                }
            }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RailIconButton(
    tab: RailTab,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (isSelected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant
    val label = railLabel(tab)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(24.dp)
                .background(
                    if (isSelected) MaterialTheme.colorScheme.primary
                    else Color.Transparent,
                ),
        )
        // material3 `TooltipBox` is commonMain-safe; it positions the
        // popup relative to the anchor (the icon button), which is the
        // conventional desktop pattern. A cursor-anchored variant would
        // need `rememberCursorPositionProvider`, which lives in
        // `ui-desktop` only — phase 5 can revisit with an
        // expect/actual provider if we want it. The icon's
        // `contentDescription` continues to feed screen readers.
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text(label) } },
            state = rememberTooltipState(),
        ) {
            IconButton(onClick = onClick) {
                Icon(
                    imageVector = railIcon(tab),
                    contentDescription = label,
                    tint = tint,
                )
            }
        }
    }
}

private fun railIcon(tab: RailTab): ImageVector = when (tab) {
    // All four icons live in material-icons-core (always shipped) so
    // they're outside the slim-task's keep-list and don't risk an R8
    // strip on Android. (Android phones won't render the rail anyway,
    // but the tablet build does.)
    RailTab.Chats -> Icons.Filled.Home
    RailTab.Statuses -> Icons.Filled.Person
    RailTab.Bookmarks -> Icons.Filled.Star
    RailTab.Activity -> Icons.Filled.Notifications
}

private fun railLabel(tab: RailTab): String = when (tab) {
    RailTab.Chats -> "Chats"
    RailTab.Statuses -> "Statuses"
    RailTab.Bookmarks -> "Bookmarks"
    RailTab.Activity -> "Activity"
}

private val RAIL_WIDTH = 64.dp
private val RIGHT_SIDEBAR_WIDTH = 360.dp
