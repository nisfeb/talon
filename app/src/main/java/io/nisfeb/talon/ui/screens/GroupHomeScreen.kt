package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.TalonApplication
import io.nisfeb.talon.data.ChannelGroupEntity
import io.nisfeb.talon.ui.Avatar
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Landing screen for a single group, used when the user taps a group
 * reference. Two modes:
 *
 * - **Member**: show the group's channels, tap to open.
 * - **Non-member**: show a "Join group" CTA that pokes `group-knock`.
 *
 * No navigation happens inside the group itself — we defer to the
 * home list's All tab for folder-y browsing; this screen is a lightly
 * curated landing page.
 */
@Composable
fun GroupHomeScreen(
    flag: String,
    onBack: () -> Unit,
    onOpenChannel: (nest: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as TalonApplication
    val scope = rememberCoroutineScope()

    val group by remember(flag) {
        app.db.groups().streamGroup(flag).distinctUntilChanged()
    }.collectAsState(initial = null)
    val channels by remember(flag) {
        app.db.groups().streamChannelsForGroup(flag).distinctUntilChanged()
    }.collectAsState(initial = emptyList())

    var joining by remember { mutableStateOf(false) }
    var joinError by remember { mutableStateOf<String?>(null) }
    var joinRequested by remember { mutableStateOf(false) }

    val title = group?.title ?: flag
    val isMember = group != null

    Column(modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            val g = group
            val isHexTint = g?.image?.startsWith("#") == true
            Avatar(
                label = title,
                url = if (isHexTint) null else g?.image,
                colorHex = if (isHexTint) g?.image else null,
                size = 36.dp,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    maxLines = 1,
                )
                if (!isMember) {
                    Text(
                        flag,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
        HorizontalDivider()

        when {
            isMember -> {
                if (channels.isEmpty()) {
                    Text(
                        "No channels in this group yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        items(items = channels, key = { it.nest }) { ch ->
                            ChannelRow(
                                ch = ch,
                                onClick = { onOpenChannel(ch.nest) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
            joinRequested -> {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Requested to join.",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                    Text(
                        "Public groups will land in your list automatically. " +
                            "For private groups the host needs to approve.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "You haven't joined this group.",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                    Text(
                        "Request access below. Public groups add you straight away; " +
                            "private groups wait on a host.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        enabled = !joining,
                        onClick = {
                            joining = true
                            joinError = null
                            scope.launch {
                                runCatching { app.repo.knockGroup(flag) }
                                    .onSuccess { joinRequested = true }
                                    .onFailure {
                                        joinError = it.message ?: it::class.simpleName
                                    }
                                joining = false
                            }
                        },
                    ) {
                        if (joining) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.height(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Join group")
                    }
                    joinError?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    ch: ChannelGroupEntity,
    onClick: () -> Unit,
) {
    val (typeLabel, typeColor) = when {
        ch.nest.startsWith("diary/") ->
            "Notebook" to MaterialTheme.colorScheme.tertiaryContainer
        ch.nest.startsWith("heap/") ->
            "Gallery" to MaterialTheme.colorScheme.secondaryContainer
        else -> null to MaterialTheme.colorScheme.primaryContainer
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            ch.title?.takeIf { it.isNotBlank() }
                ?: ("#" + ch.nest.substringAfterLast('/')),
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        typeLabel?.let {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = typeColor,
            ) {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

