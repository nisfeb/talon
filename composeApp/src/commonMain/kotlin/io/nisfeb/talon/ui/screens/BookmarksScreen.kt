// Verbatim copy — pure Compose. Keep in sync until app/ is removed.

package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import io.nisfeb.talon.ui.combinedClickableWithSecondary
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import io.nisfeb.talon.data.BookmarkFolderEntity
import io.nisfeb.talon.data.BookmarkedMessage
import io.nisfeb.talon.ui.Avatar
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.contactMapFlow
import io.nisfeb.talon.urbit.StoryCache
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookmarksScreen(
    db: AppDatabase,
    repo: TlonChatRepo,
    onOpenConversation: (whom: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val bookmarks by remember {
        db.bookmarks().streamBookmarked()
    }.collectAsState(initial = emptyList())
    val folders by remember {
        db.bookmarkFolders().streamFolders()
    }.collectAsState(initial = emptyList<BookmarkFolderEntity>())
    val members by remember {
        db.bookmarkFolders().streamMembers()
    }.collectAsState(initial = emptyList())

    val contactMap by remember {
        contactMapFlow(
            db.contacts().stream(),
            db.clubs().stream(),
            db.groups().streamGroups(),
            db.groups().streamChannelGroups(),
        )
    }.collectAsState(initial = ContactMap.EMPTY)

    /** null = "All" filter; non-null = a specific folder id. */
    var selectedFolderId by remember { mutableStateOf<Long?>(null) }

    /** Bookmark currently being assigned-to-folders via the bottom sheet. */
    var assignTarget by remember { mutableStateOf<BookmarkedMessage?>(null) }

    /** Folder being renamed / deleted via long-press menu on its chip. */
    var folderMenuFor by remember { mutableStateOf<BookmarkFolderEntity?>(null) }
    var renamingFolder by remember { mutableStateOf<BookmarkFolderEntity?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    var confirmDeleteFolder by remember { mutableStateOf<BookmarkFolderEntity?>(null) }

    // Index members by folder + by bookmark for fast filter / lookups.
    val folderMemberSet = remember(members, selectedFolderId) {
        if (selectedFolderId == null) emptySet()
        else members.asSequence()
            .filter { it.folderId == selectedFolderId }
            .map { "${it.whom}:${it.postId}" }
            .toSet()
    }
    val filtered = remember(bookmarks, selectedFolderId, folderMemberSet) {
        if (selectedFolderId == null) bookmarks
        else bookmarks.filter { "${it.whom}:${it.id}" in folderMemberSet }
    }

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

        // Folder filter chips. "All" first; each user folder; "+ New"
        // last to create a new folder. Long-press a folder chip to
        // rename / delete it.
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) {
            item(key = "__all") {
                FilterChip(
                    selected = selectedFolderId == null,
                    onClick = { selectedFolderId = null },
                    label = { Text("All") },
                )
            }
            items(items = folders, key = { "fld:${it.id}" }) { f ->
                FolderChipWithMenu(
                    folder = f,
                    selected = selectedFolderId == f.id,
                    onClick = { selectedFolderId = f.id },
                    onLongPress = { folderMenuFor = f },
                )
            }
            item(key = "__new") {
                FilterChip(
                    selected = false,
                    onClick = { creatingFolder = true },
                    label = { Text("+ New") },
                )
            }
        }
        HorizontalDivider()

        if (filtered.isEmpty()) {
            Text(
                if (bookmarks.isEmpty())
                    "No bookmarks yet. Long-press a message to bookmark it."
                else
                    "No bookmarks in this folder yet. Long-press a bookmark below to add it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(
                    items = filtered,
                    key = { "${it.whom}:${it.id}" },
                ) { b ->
                    BookmarkRow(
                        b = b,
                        contactMap = contactMap,
                        onClick = { onOpenConversation(b.whom) },
                        onLongClick = { assignTarget = b },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    // ───────── Folder long-press menu (rename / delete) ─────────
    folderMenuFor?.let { f ->
        DropdownMenu(
            expanded = true,
            onDismissRequest = { folderMenuFor = null },
        ) {
            DropdownMenuItem(
                text = { Text("Rename") },
                onClick = {
                    renamingFolder = f
                    folderMenuFor = null
                },
            )
            DropdownMenuItem(
                text = { Text("Delete folder") },
                onClick = {
                    confirmDeleteFolder = f
                    folderMenuFor = null
                },
            )
        }
    }

    // ───────── Create folder dialog ─────────
    if (creatingFolder) {
        FolderNameDialog(
            initial = "",
            title = "New folder",
            onDismiss = { creatingFolder = false },
            onConfirm = { name ->
                creatingFolder = false
                if (name.isNotBlank()) {
                    scope.launch {
                        repo.settingsSync?.createBookmarkFolder(name.trim())
                    }
                }
            },
        )
    }

    // ───────── Rename folder dialog ─────────
    renamingFolder?.let { f ->
        FolderNameDialog(
            initial = f.name,
            title = "Rename folder",
            onDismiss = { renamingFolder = null },
            onConfirm = { name ->
                renamingFolder = null
                if (name.isNotBlank() && name.trim() != f.name) {
                    scope.launch {
                        repo.settingsSync?.renameBookmarkFolder(f.id, name.trim())
                    }
                }
            },
        )
    }

    // ───────── Confirm delete folder ─────────
    confirmDeleteFolder?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmDeleteFolder = null },
            title = { Text("Delete '${f.name}'?") },
            text = {
                Text("This deletes the folder and removes its bookmark groupings. The bookmarks themselves stay.")
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = f
                    confirmDeleteFolder = null
                    if (selectedFolderId == target.id) selectedFolderId = null
                    scope.launch {
                        repo.settingsSync?.deleteBookmarkFolder(target.id)
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteFolder = null }) { Text("Cancel") }
            },
        )
    }

    // ───────── Long-press: assign-to-folders sheet ─────────
    assignTarget?.let { target ->
        val targetKey = "${target.whom}:${target.id}"
        val foldersForBookmark = remember(members, targetKey) {
            members.asSequence()
                .filter { "${it.whom}:${it.postId}" == targetKey }
                .map { it.folderId }
                .toSet()
        }
        AssignToFoldersSheet(
            target = target,
            allFolders = folders,
            currentlyIn = foldersForBookmark,
            onDismiss = { assignTarget = null },
            onToggle = { folder, checked ->
                scope.launch {
                    if (checked) {
                        repo.settingsSync?.addBookmarkToFolder(folder.id, target.whom, target.id)
                    } else {
                        repo.settingsSync?.removeBookmarkFromFolder(folder.id, target.whom, target.id)
                    }
                }
            },
            onCreateNew = { name ->
                scope.launch {
                    repo.settingsSync?.let { ss ->
                        val newId = ss.createBookmarkFolder(name.trim())
                        ss.addBookmarkToFolder(newId, target.whom, target.id)
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderChipWithMenu(
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
private fun AssignToFoldersSheet(
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
private fun FolderNameDialog(
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
private fun BookmarkRow(
    b: BookmarkedMessage,
    contactMap: ContactMap,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val body = remember(b.id, b.contentJson) {
        StoryCache.textFor(b.id, b.contentJson).replace('\n', ' ')
    }
    val title = remember(b.whom, contactMap) { contactMap.conversationLabel(b.whom) }
    val authorLabel = remember(b.author, contactMap) { contactMap.displayName(b.author) }
    val sentStamp = remember(b.sentMs) { DATE_FORMAT.format(Date(b.sentMs)) }
    val avatar = remember(b.whom, contactMap) { contactMap.conversationAvatar(b.whom) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickableWithSecondary(onClick = onClick, onLongClick = onLongClick)
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
}

private val DATE_FORMAT = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())
