package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.BookmarkFolderEntity
import io.nisfeb.talon.data.BookmarkedMessage
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.contactMapFlow
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.launch

/**
 * The list body of [BookmarksScreen], extracted so the desktop /
 * tablet-landscape rail can render it without the screen-level
 * header. Mobile + compact-mode wide go through [BookmarksScreen]
 * which wraps this with the existing TopAppBar + back-arrow.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookmarksList(
    db: AppDatabase,
    repo: TlonChatRepo,
    onOpenConversation: (whom: String) -> Unit,
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

    Column(modifier = modifier) {
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
