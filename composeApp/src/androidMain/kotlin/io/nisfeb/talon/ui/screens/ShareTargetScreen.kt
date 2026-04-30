package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.ShareIntent
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.ui.Avatar
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.contactMapFlow

/**
 * Telegram-style "share to which chat" picker. Lists conversations in
 * recency order (same query the DM list uses) and tap-to-send. Text
 * shares prefill the composer; image shares kick off an upload.
 */
@Composable
fun ShareTargetScreen(
    db: AppDatabase,
    share: ShareIntent,
    onPick: (whom: String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val conversations by remember {
        db.messages().conversationLatest()
    }.collectAsState(initial = emptyList<MessageEntity>())
    val dedupedConversations = remember(conversations) {
        conversations.distinctBy { it.whom }
    }

    val contactMap by remember {
        contactMapFlow(
            db.contacts().stream(),
            db.clubs().stream(),
            db.groups().streamGroups(),
            db.groups().streamChannelGroups(),
        )
    }.collectAsState(initial = ContactMap.EMPTY)

    var query by remember { mutableStateOf("") }
    val filtered = remember(dedupedConversations, contactMap, query) {
        val q = query.trim()
        if (q.isEmpty()) dedupedConversations
        else dedupedConversations.filter { m ->
            val label = contactMap.conversationLabel(m.whom)
            // Match on the displayed "Group · #Channel" label so a
            // search for either side hits, plus the raw whom so power
            // users can paste a patp / nest.
            label.contains(q, ignoreCase = true) ||
                m.whom.contains(q, ignoreCase = true)
        }
    }

    Column(modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
            }
            Text(
                "Share to…",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        HorizontalDivider()

        // Preview of what's being shared.
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            val previewLabel = when (share) {
                is ShareIntent.Text -> share.text
                is ShareIntent.Image -> "📎 Image"
                is ShareIntent.File -> "📎 File (${share.mimeType})"
            }
            Text(
                "Sharing",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                previewLabel,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
            )
        }
        HorizontalDivider()

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search by group or channel name") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )

        if (filtered.isEmpty() && query.isNotBlank()) {
            Text(
                "No matches.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(
                    items = filtered,
                    key = { it.whom },
                    contentType = { "conv" },
                ) { m ->
                    ShareRow(
                        whom = m.whom,
                        contactMap = contactMap,
                        onClick = { onPick(m.whom) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ShareRow(
    whom: String,
    contactMap: ContactMap,
    onClick: () -> Unit,
) {
    val title = remember(whom, contactMap) { contactMap.conversationLabel(whom) }
    val avatar = remember(whom, contactMap) { contactMap.conversationAvatar(whom) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(
            label = title,
            url = avatar,
            colorHex = contactMap.conversationColor(whom),
            size = 40.dp,
        )
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
        )
    }
}
