package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.ui.screens.MenuBadgeDot

/**
 * The sections, as a drawer.
 *
 * The same list the desktop rail shows, in the same order, under the
 * same names and icons, hidden and reordered by the same preferences.
 * Two presentations of one set of sections rather than two lists that
 * drift: somebody who turns Watchwords off on their desktop has turned
 * it off, not turned it off in one of the two places it appears.
 *
 * Only built where [isDrawerNavigation] is true. A desktop has the
 * rail, and a hamburger beside it would be a second way to do one
 * thing.
 */
@Composable
fun SectionsDrawer(
    order: List<RailItem>,
    visibility: Map<RailItem, Boolean>,
    /** Which one the app is showing, so the drawer can mark it. */
    active: RailItem?,
    /** What each section does. A section with no action is one this
     *  host cannot reach, and it is left out rather than offered. */
    onSection: (RailItem) -> Unit,
    canOpen: (RailItem) -> Boolean = { true },
    badges: Map<RailItem, Boolean> = emptyMap(),
    header: @Composable (() -> Unit)? = null,
    footer: @Composable (() -> Unit)? = null,
) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        header?.invoke()
        val shown = drawerSections(order, visibility, canOpen)
        shown.forEach { item ->
            NavigationDrawerItem(
                selected = item == active,
                label = {
                    Text(
                        railLabel(item),
                        fontWeight = if (item == active) FontWeight.SemiBold else FontWeight.Normal,
                    )
                },
                icon = {
                    val icon = railIcon(item)
                    if (icon != null) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
                    } else {
                        // The assistant has no glyph of its own; the
                        // rail draws it as a letter and so does this.
                        Text("A", style = MaterialTheme.typography.titleMedium)
                    }
                },
                badge = if (badges[item] == true) {
                    { MenuBadgeDot() }
                } else {
                    null
                },
                onClick = { onSection(item) },
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        if (footer != null) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(8.dp))
            footer()
        }
    }
}
