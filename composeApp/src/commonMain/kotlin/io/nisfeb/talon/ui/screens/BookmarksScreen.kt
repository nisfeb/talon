package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import io.nisfeb.talon.ui.combinedClickableWithSecondary
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.BookmarkFolderEntity
import io.nisfeb.talon.data.BookmarkedMessage
import io.nisfeb.talon.ui.Avatar
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.urbit.StoryCache
import io.nisfeb.talon.urbit.TlonChatRepo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookmarksScreen(
    db: AppDatabase,
    repo: TlonChatRepo,
    onOpenConversation: (whom: String, postId: String?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Bookmarks",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        HorizontalDivider()
        BookmarksList(
            db = db,
            repo = repo,
            onOpenConversation = onOpenConversation,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FolderChipWithMenu(
    folder: BookmarkFolderEntity,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    // FilterChip doesn't have a long-press hook, so we wrap with a
    // combinedClickable Modifier on a Row that visually mimics the chip
    // selection state via the underlying FilterChip.
    Row(
        modifier = Modifier.combinedClickableWithSecondary(
            onClick = onClick,
            onLongClick = onLongPress,
        ),
    ) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            label = { Text(folder.name) },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun AssignToFoldersSheet(
    target: BookmarkedMessage,
    allFolders: List<BookmarkFolderEntity>,
    currentlyIn: Set<Long>,
    onDismiss: () -> Unit,
    onToggle: (BookmarkFolderEntity, Boolean) -> Unit,
    onCreateNew: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var newName by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Add to folder", style = MaterialTheme.typography.titleMedium)
            if (allFolders.isEmpty()) {
                Text(
                    "No folders yet. Create one below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            allFolders.forEach { f ->
                val isIn = f.id in currentlyIn
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(f, !isIn) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(checked = isIn, onCheckedChange = { onToggle(f, it) })
                    Text(f.name, style = MaterialTheme.typography.bodyLarge)
                }
            }
            HorizontalDivider()
            Text("New folder", style = MaterialTheme.typography.labelMedium)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text("Folder name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        if (newName.isNotBlank()) {
                            onCreateNew(newName)
                            newName = ""
                        }
                    },
                    enabled = newName.isNotBlank(),
                ) { Text("Create + add") }
            }
        }
    }
}

@Composable
internal fun FolderNameDialog(
    initial: String,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("Folder name") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BookmarkRow(
    b: BookmarkedMessage,
    contactMap: ContactMap,
    onClick: () -> Unit,
    onAssignToFolder: () -> Unit,
) {
    val fullBody = remember(b.id, b.contentJson) {
        StoryCache.textFor(b.id, b.contentJson)
    }
    val body = remember(fullBody) { fullBody.replace('\n', ' ') }
    val title = remember(b.whom, contactMap) { contactMap.conversationLabel(b.whom) }
    val authorLabel = remember(b.author, contactMap) { contactMap.displayName(b.author) }
    val sentStamp = remember(b.sentMs) { DATE_FORMAT.format(Date(b.sentMs)) }
    val avatar = remember(b.whom, contactMap) { contactMap.conversationAvatar(b.whom) }
    val clipboard = LocalClipboardManager.current
    var menuOpen by remember(b.id, b.whom) { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickableWithSecondary(
                    onClick = onClick,
                    onLongClick = { menuOpen = true },
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(label = title, url = avatar, size = 40.dp)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    "$authorLabel · $sentStamp",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                )
            }
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            DropdownMenuItem(
                text = { Text("Copy text") },
                enabled = fullBody.isNotEmpty(),
                onClick = {
                    clipboard.setText(AnnotatedString(fullBody))
                    menuOpen = false
                },
            )
            DropdownMenuItem(
                text = { Text("Add to folder…") },
                onClick = {
                    menuOpen = false
                    onAssignToFolder()
                },
            )
        }
    }
}

private val DATE_FORMAT = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())
