package io.nisfeb.talon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity

/**
 * "Which chat?": the conversations in recency order, searched by
 * their label or their whom, one tap to choose. [onlyGroups] keeps
 * it to group channels, for a thing meant for everyone in a group.
 */
@Composable
fun PickConversationDialog(
    db: AppDatabase,
    title: String,
    onlyGroups: Boolean = false,
    onDismiss: () -> Unit,
    onPick: (whom: String) -> Unit,
) {
    val conversations by remember { db.messages().conversationLatest() }.collectAsState(initial = emptyList<MessageEntity>())
    val contactMap by rememberContactMap(db)
    var query by remember { mutableStateOf("") }
    val rows = remember(conversations, contactMap, query, onlyGroups) {
        val q = query.trim()
        conversations.distinctBy { it.whom }
            .filterNot { it.whom.startsWith("diary/") || it.whom.startsWith("notes/") }
            .filter { !onlyGroups || it.whom.startsWith("chat/") }
            .filter { q.isEmpty() || contactMap.conversationLabel(it.whom).contains(q, ignoreCase = true) || it.whom.contains(q, ignoreCase = true) }
            .take(60)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(value = query, onValueChange = { query = it }, placeholder = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.fillMaxWidth().height(320.dp).padding(top = 6.dp)) {
                    if (rows.isEmpty()) item { Text("Nothing here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp)) }
                    items(rows, key = { it.whom }) { m ->
                        Text(
                            contactMap.conversationLabel(m.whom),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(m.whom) }.padding(horizontal = 8.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
