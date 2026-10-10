package io.nisfeb.talon.ui

import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nisfeb.talon.ui.icons.TalonIcons

/**
 * Desktop / tablet-landscape host. Below [ExpandedThreshold] this is a
 * passthrough: we render only [detail] when set, otherwise [list] —
 * matching the stack-style mobile flow Phase 1 already established.
 *
 * At/above the threshold we add a 64dp vertical icon rail on the far
 * left, then defer to [ChatPaneScaffold] for the list / detail split
 * with its drag handle, and put [rightSidebar] (thread, group info) on
 * the right.
 *
 * As the window narrows, in order: the list and the right pane give up
 * width down to their minimums; then, below [RIGHT_BESIDE_WIDTH], the
 * right pane opens over the chat instead of beside it; then, below
 * [ExpandedThreshold], everything is full-screen. The chat column never
 * gets narrower than [MIN_CHAT_WIDTH], nor does a desktop window.
 *
 * The rail is rendered ONLY inside the expanded branch — never as a
 * sibling of [ChatPaneScaffold] — so a compact-mode resize doesn't
 * leave a dangling 64dp gutter on phones / narrow desktop windows.
 */
@Composable
fun DesktopShell(
    activeRailTab: RailTab,
    // A modal rail item (e.g. Assistant) whose destination is currently
    // showing — highlighted instead of the active pane-tab so the rail
    // reflects where you are. Null when a pane-tab is the active surface.
    activeModalItem: RailItem? = null,
    enabledItems: List<RailItem>,
    onItemClicked: (RailItem) -> Unit,
    list: @Composable () -> Unit,
    detail: (@Composable () -> Unit)?,
    listFraction: Float,
    onListFractionChange: (Float) -> Unit,
    rightSidebar: (@Composable () -> Unit)? = null,
    // The right pane's width; dragged at its left edge like the list pane.
    rightPaneWidth: Dp = DEFAULT_RIGHT_PANE_WIDTH,
    onRightPaneWidthChange: (Dp) -> Unit = {},
    menuBadges: MenuBadges = MenuBadges(),
    // Full-width content that takes over the whole area beside the rail,
    // bypassing the list/detail split — for a screen that manages its own
    // panes (e.g. the assistant: its own conversations/jobs sidebar + a
    // transcript). Keeps the rail visible for navigation.
    content: (@Composable () -> Unit)? = null,
    /** The screen's left and right safe areas; a test hands in its own. */
    sideInsets: WindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val expanded = maxWidth >= ExpandedThreshold
        if (!expanded) {
            // Compact: rail/sidebar collapsed; identical to Phase 1.
            when {
                content != null -> content()
                detail != null -> detail()
                else -> list()
            }
            return@BoxWithConstraints
        }
        // The right pane sits beside the chat only while list and chat
        // keep their minimums; narrower, it opens over the chat, as it
        // does full-screen in the compact layout. Its close button is
        // the way back to the chat.
        // The left and right safe areas (an iPhone on its side), taken
        // once around the rail and the panes, and so consumed: each pane
        // padded for both itself, and a list beside a chat kept about
        // half its width (sneagan, 2026-10-09). Widths are what is left.
        val density = LocalDensity.current
        val direction = LocalLayoutDirection.current
        val safeWidth = maxWidth - with(density) { (sideInsets.getLeft(this, direction) + sideInsets.getRight(this, direction)).toDp() }
        val rightWidth = rightSidebar?.let { rightPaneBesideWidth(safeWidth, rightPaneWidth) }
        val overChat = rightSidebar != null && rightWidth == null
        // The rail's colour under the left safe area, so the rail reaches
        // the screen's edge instead of floating beside a bare strip.
        val leftInset = with(density) { sideInsets.getLeft(this, direction).toDp() }
        if (leftInset > 0.dp) Box(Modifier.fillMaxHeight().width(leftInset).background(MaterialTheme.colorScheme.surfaceVariant))
        Row(modifier = Modifier.fillMaxSize().windowInsetsPadding(sideInsets)) {
            DesktopRail(
                activeTab = activeRailTab,
                activeModalItem = activeModalItem,
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
                if (content != null) {
                    content()
                } else {
                    ChatPaneScaffold(
                        list = list,
                        detail = if (overChat) rightSidebar else detail,
                        listFraction = listFraction,
                        onListFractionChange = onListFractionChange,
                        // Every width this branch sees splits: the list
                        // narrows to its minimum rather than folding away.
                        splitFrom = SPLIT_WIDTH,
                    )
                }
            }
            // Right sidebar: thread, group info or a media drilldown.
            // The caller (App.kt) only supplies a non-null lambda when
            // there's active content to show, so a no-content right pane
            // never wastes screen real estate.
            if (rightSidebar != null && rightWidth != null) {
                PaneDragHandle(onDragDelta = { deltaPx ->
                    val delta = with(density) { deltaPx.toDp() }
                    rightPaneBesideWidth(safeWidth, rightWidth - delta)
                        ?.let(onRightPaneWidthChange)
                })
                Box(modifier = Modifier.width(rightWidth).fillMaxHeight()) {
                    rightSidebar()
                }
            }
        }
    }
}

@Composable
private fun DesktopRail(
    activeTab: RailTab,
    activeModalItem: RailItem?,
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
                // A modal destination (Assistant) wins the highlight while
                // it's showing, so the previously-active pane-tab goes dark.
                val isSelected = if (activeModalItem != null) {
                    item == activeModalItem
                } else {
                    item.isPaneTab && item.toRailTab() == activeTab
                }
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
                    Icon(imageVector = railIcon(item), contentDescription = label, tint = tint)
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

internal fun railIcon(item: RailItem): ImageVector = when (item) {
    // Core icons, or ones the app owns in TalonIcons: the extended
    // set is not shipped.
    RailItem.Home -> Icons.Filled.Home
    // Chats gave up the house to the home page and took the icon
    // that actually means chat.
    RailItem.Chats -> TalonIcons.Chat
    RailItem.Mail -> Icons.Filled.MailOutline
    RailItem.Calendar -> Icons.Filled.DateRange
    RailItem.Statuses -> Icons.Filled.Person
    RailItem.Bookmarks -> Icons.Filled.Star
    RailItem.Activity -> Icons.Filled.Notifications
    // Was the letter "A": narrower than an icon, it pulled its label
    // out of line with every other one in the menu.
    RailItem.Assistant -> TalonIcons.AutoAwesome
    RailItem.Profile -> Icons.Filled.AccountCircle
    RailItem.Administration -> Icons.Filled.Build
    // Not an envelope: that reads as mail. An invite is to a group.
    RailItem.Invites -> TalonIcons.GroupAdd
    RailItem.Actions -> TalonIcons.Checklist
    RailItem.Settings -> Icons.Filled.Settings
}

internal fun railLabel(item: RailItem): String = when (item) {
    RailItem.Home -> "Home"
    RailItem.Chats -> "Chats"
    RailItem.Mail -> "Mail"
    RailItem.Calendar -> "Calendar"
    RailItem.Statuses -> "Statuses"
    RailItem.Bookmarks -> "Bookmarks"
    RailItem.Activity -> "Activity"
    RailItem.Assistant -> "Assistant"
    RailItem.Profile -> "My profile"
    RailItem.Administration -> "Administration"
    RailItem.Invites -> "Invites"
    RailItem.Actions -> "Orrery"
    RailItem.Settings -> "Settings"
}

val RAIL_WIDTH = 64.dp
val DEFAULT_RIGHT_PANE_WIDTH = 360.dp
val MIN_RIGHT_PANE_WIDTH = 280.dp
val MAX_RIGHT_PANE_WIDTH = 900.dp

/**
 * The right pane's width beside the list and chat of a [window]-wide
 * shell: the [wanted] width, held between the pane's own bounds and
 * what leaves the list and chat their minimums ([SPLIT_WIDTH]). Null
 * where even the pane's minimum does not fit beside them: the pane then
 * opens over the chat. With the minimums as they are, that is below
 * [RIGHT_BESIDE_WIDTH].
 */
fun rightPaneBesideWidth(window: Dp, wanted: Dp): Dp? {
    val room = (window - RAIL_WIDTH - SPLIT_WIDTH - HANDLE_WIDTH).coerceAtMost(MAX_RIGHT_PANE_WIDTH)
    return if (room < MIN_RIGHT_PANE_WIDTH) null else wanted.coerceIn(MIN_RIGHT_PANE_WIDTH, room)
}

/** The narrowest window with the right pane beside the chat. */
val RIGHT_BESIDE_WIDTH = RAIL_WIDTH + SPLIT_WIDTH + HANDLE_WIDTH + MIN_RIGHT_PANE_WIDTH
