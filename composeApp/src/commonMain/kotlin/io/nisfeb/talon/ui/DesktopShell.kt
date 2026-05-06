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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.draw.clip
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
    enabledItems: List<RailItem>,
    onItemClicked: (RailItem) -> Unit,
    list: @Composable () -> Unit,
    detail: (@Composable () -> Unit)?,
    listFraction: Float,
    onListFractionChange: (Float) -> Unit,
    rightSidebar: (@Composable () -> Unit)? = null,
    menuBadges: MenuBadges = MenuBadges(),
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
                enabledItems = enabledItems,
                onItemClicked = onItemClicked,
                menuBadges = menuBadges,
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
    enabledItems: List<RailItem>,
    onItemClicked: (RailItem) -> Unit,
    menuBadges: MenuBadges,
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
            for (item in enabledItems) {
                val isSelected = item.isPaneTab && item.toRailTab() == activeTab
                RailIconButton(
                    item = item,
                    isSelected = isSelected,
                    showBadge = menuBadges.forItem(item),
                    onClick = { onItemClicked(item) },
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RailIconButton(
    item: RailItem,
    isSelected: Boolean,
    showBadge: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (isSelected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant
    val label = railLabel(item)
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
            Box {
                IconButton(onClick = onClick) {
                    Icon(
                        imageVector = railIcon(item),
                        contentDescription = label,
                        tint = tint,
                    )
                }
                if (showBadge) {
                    // 8dp dot on the icon's top-right corner. The
                    // IconButton is 48dp; the icon glyph is 24dp
                    // centered. Offsetting from TopEnd by (-12,12)
                    // nestles the dot on the icon corner instead of
                    // the button's outer edge, matching the kebab
                    // badge's visual weight.
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = (-12).dp, y = 12.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
    }
}

private fun railIcon(item: RailItem): ImageVector = when (item) {
    // All icons resolve to material-icons-core (verified by
    // unzipping the core jar at plan-write time). Safe with the
    // slim-jar strip; auditIconKeepList catches any drift.
    RailItem.Chats -> Icons.Filled.Home
    RailItem.Statuses -> Icons.Filled.Person
    RailItem.Bookmarks -> Icons.Filled.Star
    RailItem.Activity -> Icons.Filled.Notifications
    RailItem.Profile -> Icons.Filled.AccountCircle
    RailItem.Watchwords -> Icons.Filled.Search
    RailItem.TodaysBrief -> Icons.Filled.DateRange
    RailItem.Administration -> Icons.Filled.Build
    RailItem.Invites -> Icons.Filled.Email
    RailItem.Settings -> Icons.Filled.Settings
}

private fun railLabel(item: RailItem): String = when (item) {
    RailItem.Chats -> "Chats"
    RailItem.Statuses -> "Statuses"
    RailItem.Bookmarks -> "Bookmarks"
    RailItem.Activity -> "Activity"
    RailItem.Profile -> "My profile"
    RailItem.Watchwords -> "Watchwords"
    RailItem.TodaysBrief -> "Today's brief"
    RailItem.Administration -> "Administration"
    RailItem.Invites -> "Invites"
    RailItem.Settings -> "Settings"
}

private val RAIL_WIDTH = 64.dp
private val RIGHT_SIDEBAR_WIDTH = 360.dp
