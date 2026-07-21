package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.nisfeb.talon.data.NotesFolderEntity
import io.nisfeb.talon.data.NotesNoteEntity
import io.nisfeb.talon.urbit.NotesFlag
import io.nisfeb.talon.urbit.TlonChatRepo
import io.nisfeb.talon.util.formatMonthDayYear
import kotlinx.coroutines.launch

/**
 * One %notes notebook: a folder tree with Markdown notes at the leaves.
 *
 * Navigation is a folder stack rather than a nested tree view — on a
 * phone an indented tree runs out of width fast, and "open a folder,
 * back out" matches how the rest of the app already moves. Back pops a
 * folder first and only leaves the channel at the root.
 */
@Composable
fun NotesChannelScreen(
    repo: TlonChatRepo,
    whom: String,
    onBack: () -> Unit,
    onOpenNote: (noteId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val flag = remember(whom) { NotesFlag.parse(whom) }
    if (flag == null) {
        // Shouldn't happen — the caller only routes notes/ nests here —
        // but render something rather than crashing on a malformed nest.
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Not a notebook: $whom")
        }
        return
    }

    val scope = rememberCoroutineScope()
    val notebook by remember(flag) { repo.notes.streamNotebook(flag) }
        .collectAsState(initial = null)
    val folders by remember(flag) { repo.notes.streamFolders(flag) }
        .collectAsState(initial = emptyList())
    val notes by remember(flag) { repo.notes.streamNotes(flag) }
        .collectAsState(initial = emptyList())

    // Folder navigation stack; empty = notebook root.
    var stack by remember(flag) { mutableStateOf<List<NotesFolderEntity>>(emptyList()) }
    // Host tells us the root directly; the null-parent scan is only a
    // fallback for a row cached before rootFolderId was stored.
    val rootFolderId = notebook?.rootFolderId
        ?: remember(folders) { folders.firstOrNull { it.parentFolderId == null }?.folderId }
    val currentFolderId = stack.lastOrNull()?.folderId ?: rootFolderId

    val childFolders = remember(folders, currentFolderId) {
        folders.filter { it.parentFolderId == currentFolderId }.sortedBy { it.name.lowercase() }
    }
    val folderNotes = remember(notes, currentFolderId) {
        notes.filter { it.folderId == currentFolderId }.sortedBy { it.title.lowercase() }
    }

    // A notes channel listed in a group isn't readable until we join it
    // on %notes (the host only serves notebooks in our own books map).
    // Joining is idempotent, so this is safe on every open.
    LaunchedEffect(flag) { repo.notes.ensureJoined(flag) }

    var addMenuOpen by remember { mutableStateOf(false) }
    var newFolderOpen by remember { mutableStateOf(false) }
    var newNoteOpen by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                if (stack.isNotEmpty()) stack = stack.dropLast(1) else onBack()
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stack.lastOrNull()?.name ?: notebook?.title ?: flag.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (stack.isNotEmpty()) {
                    Text(
                        notebook?.title ?: flag.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // One "+" that asks what to add. Two bare icon buttons here
            // (plus = folder, pencil = note) read as "add" and "edit", so
            // reaching for the obvious one created a folder every time.
            Box {
                IconButton(onClick = { addMenuOpen = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add")
                }
                DropdownMenu(
                    expanded = addMenuOpen,
                    onDismissRequest = { addMenuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("New note") },
                        onClick = {
                            addMenuOpen = false
                            newNoteOpen = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("New folder") },
                        onClick = {
                            addMenuOpen = false
                            newFolderOpen = true
                        },
                    )
                }
            }
        }

        if (childFolders.isEmpty() && folderNotes.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (notebook == null) {
                        "This notebook isn't synced yet."
                    } else {
                        "Nothing here yet."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(childFolders, key = { "f${it.folderId}" }) { f ->
                    FolderRow(f) { stack = stack + f }
                }
                items(folderNotes, key = { "n${it.noteId}" }) { n ->
                    NoteRow(n) { onOpenNote(n.noteId) }
                }
            }
        }
    }

    if (newFolderOpen) {
        NameDialog(
            title = "New folder",
            label = "Folder name",
            onDismiss = { newFolderOpen = false },
            onConfirm = { name ->
                newFolderOpen = false
                val parent = currentFolderId ?: return@NameDialog
                scope.launch { repo.notes.createFolder(flag, parent, name) }
            },
        )
    }
    if (newNoteOpen) {
        NameDialog(
            title = "New note",
            label = "Title",
            onDismiss = { newNoteOpen = false },
            onConfirm = { title ->
                newNoteOpen = false
                val parent = currentFolderId ?: return@NameDialog
                scope.launch { repo.notes.createNote(flag, parent, title, "") }
            },
        )
    }
}

@Composable
private fun FolderRow(folder: NotesFolderEntity, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("📁", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(12.dp))
        Text(folder.name, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun NoteRow(note: NotesNoteEntity, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("📄", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    note.title.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (note.pending) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "saving…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (note.updatedAtMs > 0) {
                Text(
                    formatMonthDayYear(note.updatedAtMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    label: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text.trim()) },
                enabled = text.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
