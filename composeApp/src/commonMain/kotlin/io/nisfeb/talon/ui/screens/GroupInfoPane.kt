package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.NotifyLevel
import io.nisfeb.talon.urbit.MediaCategory
import io.nisfeb.talon.urbit.TlonChatRepo
import io.nisfeb.talon.urbit.mediaCategoryOrLink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Group info body: header + mute toggle + member-count link + media
 * stats + leave button. Hosted by `RightPaneHost` on wide and by
 * `GroupInfoScreen` on compact (both arrive in later Phase 3 tasks).
 *
 * `whom` is a channel nest (e.g. `chat/~host/slug`). The pane resolves
 * the enclosing group's `flag` via [io.nisfeb.talon.data.GroupDao.channelGroupFor]
 * — the caller doesn't have to know about the channel→group mapping.
 * If no mapping exists (DM, club, or a freshly-bootstrapped channel
 * the %groups scry hasn't covered yet) the leave row hides itself.
 *
 * Member count is fetched once via [TlonChatRepo.fetchGroupAdmin]
 * because Talon doesn't persist a member table — the count is a
 * snapshot, not a live stream. Good enough for a side pane that
 * already costs a network round-trip when it opens.
 */
@Composable
fun GroupInfoPane(
    db: AppDatabase,
    repo: TlonChatRepo,
    whom: String,
    onOpenCategory: (MediaCategory) -> Unit,
    onOpenMembers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    // Resolve channel → group flag once per whom. Null means we
    // either don't know the group yet or this isn't a group channel
    // (DM/club). The leave row hides in that case.
    var groupFlag by remember(whom) { mutableStateOf<String?>(null) }
    var memberCount by remember(whom) { mutableStateOf(0) }
    LaunchedEffect(whom) {
        val mapping = runCatching { db.groups().channelGroupFor(whom) }.getOrNull()
        groupFlag = mapping?.groupFlag
        val flag = mapping?.groupFlag ?: return@LaunchedEffect
        runCatching { repo.fetchGroupAdmin(flag) }
            .getOrNull()
            ?.let { memberCount = it.members.size }
    }

    val groupRowFlow: Flow<io.nisfeb.talon.data.GroupEntity?> =
        remember(groupFlag) {
            val flag = groupFlag
            if (flag == null) flowOf(null) else db.groups().streamGroup(flag)
        }
    val groupRow by groupRowFlow.collectAsState(initial = null)

    val notifyPref by remember(whom) { db.notifyPrefs().stream(whom) }
        .collectAsState(initial = null)
    val countsList by remember(whom) {
        db.messageMedia().streamCounts(whom)
    }.collectAsState(initial = emptyList())

    val countsByCategory = remember(countsList) {
        countsList.associate { mediaCategoryOrLink(it.category) to it.n }
    }

    LazyColumn(modifier = modifier.fillMaxWidth()) {
        item {
            // Header: title + member count. Avatar is intentionally
            // omitted in v1; can be added later by reading
            // groupRow.image (URL or hex tint) once we settle on the
            // shared avatar component.
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    groupRow?.title ?: whom,
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "$memberCount members",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
        }

        item {
            // Mute toggle row. Talon's notify-level enum is
            // ALL/MENTIONS/NONE; "muted" means NONE, "default" means
            // MENTIONS (the standard Tlon-equivalent for chats).
            val level = notifyPref?.level ?: NotifyLevel.DEFAULT
            val muted = level == NotifyLevel.NONE
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (muted) Icons.Filled.NotificationsOff else Icons.Filled.Notifications,
                    contentDescription = null,
                )
                Spacer(Modifier.size(12.dp))
                Text("Mute", modifier = Modifier.weight(1f))
                Switch(
                    checked = muted,
                    onCheckedChange = { newMuted ->
                        scope.launch {
                            val next = if (newMuted) NotifyLevel.NONE else NotifyLevel.DEFAULT
                            runCatching { repo.settingsSync?.setNotifyLevel(whom, next) }
                        }
                    },
                )
            }
            HorizontalDivider()
        }

        item {
            // Members link → opens existing GroupAdminScreen via the
            // caller-supplied onOpenMembers handler.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenMembers)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.People, contentDescription = null)
                Spacer(Modifier.size(12.dp))
                Text(
                    "View members ($memberCount)",
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Open members",
                )
            }
            HorizontalDivider()
        }

        item {
            Text(
                "Shared media",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }

        item {
            MediaStatsGrid(
                counts = countsByCategory,
                onSelect = onOpenCategory,
            )
        }

        // Leave group only makes sense if we actually resolved a group
        // flag. Hides for DMs and pre-bootstrap channels.
        groupFlag?.let { flag ->
            item {
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            scope.launch {
                                runCatching { repo.leaveGroup(flag) }
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ExitToApp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.size(12.dp))
                    Text(
                        "Leave group",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MediaStatsGrid(
    counts: Map<MediaCategory, Int>,
    onSelect: (MediaCategory) -> Unit,
) {
    // Order matches the spec mock-up: Photo, Video, Gif, Voice, Audio,
    // File, Link. Zero-count buckets are hidden.
    val entries = MediaCategory.entries
        .map { it to (counts[it] ?: 0) }
        .filter { (_, n) -> n > 0 }
    if (entries.isEmpty()) {
        Text(
            "No shared media yet",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        return
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((cat, n) in entries) {
            StatCell(category = cat, count = n, onClick = { onSelect(cat) })
        }
    }
}

@Composable
private fun StatCell(
    category: MediaCategory,
    count: Int,
    onClick: () -> Unit,
) {
    val emoji = when (category) {
        MediaCategory.Photo -> "📷"
        MediaCategory.Video -> "🎥"
        MediaCategory.Gif -> "🎞"
        MediaCategory.Voice -> "🎙"
        MediaCategory.Audio -> "🎵"
        MediaCategory.File -> "📄"
        MediaCategory.Link -> "🔗"
    }
    Box(
        modifier = Modifier
            .size(width = 64.dp, height = 64.dp)
            .padding(2.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(count.toString(), style = MaterialTheme.typography.titleMedium)
            Text(emoji, style = MaterialTheme.typography.titleSmall)
        }
    }
}
