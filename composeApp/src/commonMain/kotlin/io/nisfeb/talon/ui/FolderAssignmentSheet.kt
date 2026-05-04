package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.data.FolderEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderAssignmentSheet(
    conversationLabel: String,
    folders: List<FolderEntity>,
    selectedFolderIds: Set<Long>,
    onToggle: (FolderEntity, Boolean) -> Unit,
    onCreateNew: (name: String) -> Unit,
    onDismiss: () -> Unit,
    /** When non-null, renders a "Leave group" row at the bottom of
     *  the sheet. Only the group long-press call site provides this;
     *  per-conversation invocations leave it null. The lambda fires
     *  AFTER the user confirms in the dialog, so the host doesn't
     *  need to put up its own confirmation. */
    onLeaveGroup: (() -> Unit)? = null,
    /** When non-null, renders a "Mark group as read" row above
     *  Leave group. Only the group long-press call site provides
     *  this. The lambda fires immediately on tap — no confirmation,
     *  since marking-read is reversible (a new message restores the
     *  unread state) and asking-twice for a one-tap action is noise. */
    onMarkGroupRead: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState()
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var confirmLeave by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            Text(
                conversationLabel,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                "Folders",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            if (folders.isEmpty()) {
                Text(
                    "No folders yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(items = folders, key = { it.id }) { f ->
                        val checked = f.id in selectedFolderIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { onToggle(f, it) },
                            )
                            Text(f.name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            TextButton(
                onClick = {
                    creating = true
                    newName = ""
                },
            ) { Text("+ New folder") }

            if (onMarkGroupRead != null || onLeaveGroup != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
            if (onMarkGroupRead != null) {
                TextButton(
                    onClick = {
                        onMarkGroupRead()
                        onDismiss()
                    },
                ) {
                    Text("Mark group as read")
                }
            }
            if (onLeaveGroup != null) {
                TextButton(
                    onClick = { confirmLeave = true },
                ) {
                    Text(
                        "Leave group",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    if (confirmLeave && onLeaveGroup != null) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave $conversationLabel?") },
            text = {
                Text(
                    "You'll stop receiving messages from this group's channels. " +
                        "You can rejoin later if it's public, or ask for a new invite.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    onLeaveGroup()
                    onDismiss()
                }) {
                    Text("Leave", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) { Text("Cancel") }
            },
        )
    }

    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("New folder") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = newName.trim()
                        if (trimmed.isNotEmpty()) {
                            onCreateNew(trimmed)
                            creating = false
                        }
                    },
                    enabled = newName.isNotBlank(),
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { creating = false }) { Text("Cancel") }
            },
        )
    }
}
