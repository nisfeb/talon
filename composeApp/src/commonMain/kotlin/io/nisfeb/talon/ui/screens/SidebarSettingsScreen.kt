package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.ui.RailItem
import io.nisfeb.talon.ui.UiSettings
import io.nisfeb.talon.ui.isVisible
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.launch

@Composable
fun SidebarSettingsScreen(
    repo: TlonChatRepo,
    uiSettings: UiSettings,
    dailyDigestEnabled: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val railVisibility by uiSettings.railVisibility.collectAsState()
    Column(modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Sidebar",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        HorizontalDivider()
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(RailItem.entries.toList(), key = { it.name }) { item ->
                SidebarItemRow(
                    item = item,
                    visible = railVisibility.isVisible(item),
                    dailyDigestEnabled = dailyDigestEnabled,
                    onToggle = { newVisible ->
                        scope.launch {
                            runCatching {
                                repo.settingsSync?.setRailItemVisibility(item, newVisible)
                            }
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SidebarItemRow(
    item: RailItem,
    visible: Boolean,
    dailyDigestEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val state = sidebarRowState(item, dailyDigestEnabled)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(state.label, style = MaterialTheme.typography.bodyLarge)
            if (state.subtitle != null) {
                Text(
                    state.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when {
            state.fixedAlwaysOn -> {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        "On",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            state.gatedOff -> {
                Switch(checked = false, onCheckedChange = null, enabled = false)
            }
            else -> {
                Switch(checked = visible, onCheckedChange = { onToggle(it) })
            }
        }
    }
}

private data class SidebarRowState(
    val label: String,
    val subtitle: String?,
    val fixedAlwaysOn: Boolean,
    val gatedOff: Boolean,
)

private fun sidebarRowState(
    item: RailItem,
    dailyDigestEnabled: Boolean,
): SidebarRowState = when (item) {
    RailItem.Chats -> SidebarRowState(
        label = "Chats",
        subtitle = "Always on the sidebar",
        fixedAlwaysOn = true,
        gatedOff = false,
    )
    RailItem.TodaysBrief -> SidebarRowState(
        label = "Today's brief",
        subtitle = if (!dailyDigestEnabled) "Enable Daily Digest in Settings to use this" else null,
        fixedAlwaysOn = false,
        gatedOff = !dailyDigestEnabled,
    )
    RailItem.Statuses -> SidebarRowState("Statuses", null, false, false)
    RailItem.Bookmarks -> SidebarRowState("Bookmarks", null, false, false)
    RailItem.Activity -> SidebarRowState("Activity", null, false, false)
    RailItem.Profile -> SidebarRowState("My profile", null, false, false)
    RailItem.Watchwords -> SidebarRowState("Watchwords", null, false, false)
    RailItem.Administration -> SidebarRowState("Administration", null, false, false)
    RailItem.Invites -> SidebarRowState("Invites", null, false, false)
    RailItem.Settings -> SidebarRowState("Settings", null, false, false)
}
