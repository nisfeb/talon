package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import io.nisfeb.talon.ui.MarkdownText
import io.nisfeb.talon.urbit.NotesFlag
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.launch

/**
 * A single Markdown note: rendered for reading, raw for editing.
 *
 * Saving sends the revision the editor opened. If someone else edited
 * the note meanwhile the host rejects the write, and we surface that
 * rather than silently overwriting their work — the user keeps their
 * draft and can reconcile.
 */
@Composable
fun NoteScreen(
    repo: TlonChatRepo,
    whom: String,
    noteId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val flag = remember(whom) { NotesFlag.parse(whom) }
    if (flag == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Not a notebook: $whom")
        }
        return
    }

    val scope = rememberCoroutineScope()
    val note by remember(flag, noteId) { repo.notes.streamNote(flag, noteId) }
        .collectAsState(initial = null)

    var editing by remember(noteId) { mutableStateOf(false) }
    var draft by remember(noteId) { mutableStateOf("") }
    /** Revision the draft was opened against, for conflict detection. */
    var baseRevision by remember(noteId) { mutableStateOf(0L) }
    var conflict by remember(noteId) { mutableStateOf(false) }
    var confirmDelete by remember(noteId) { mutableStateOf(false) }

    // Seed the draft when entering edit mode, not on every emission —
    // a stream update mid-edit must not wipe what's being typed.
    LaunchedEffect(editing) {
        if (editing) {
            note?.let {
                draft = it.bodyMd
                baseRevision = it.revision
            }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { if (editing) editing = false else onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    note?.title?.ifBlank { "Untitled" } ?: "Note",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                note?.let {
                    Text(
                        if (it.pending) "saving…" else "rev ${it.revision}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (editing) {
                IconButton(
                    onClick = {
                        val body = draft
                        scope.launch {
                            val ok = repo.notes.updateNote(flag, noteId, body, baseRevision)
                            if (ok) editing = false else conflict = true
                        }
                    },
                ) { Icon(Icons.Filled.Check, contentDescription = "Save") }
            } else {
                IconButton(onClick = { editing = true }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit")
                }
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete")
                }
            }
        }

        when {
            note == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            editing -> OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxSize().padding(12.dp),
                label = { Text("Markdown") },
            )
            else -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val body = note?.bodyMd.orEmpty()
                if (body.isBlank()) {
                    Text(
                        "This note is empty.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    MarkdownText(body)
                }
            }
        }
    }

    if (conflict) {
        AlertDialog(
            onDismissRequest = { conflict = false },
            title = { Text("Couldn't save") },
            text = {
                Text(
                    "This note changed on the ship since you started editing, " +
                        "so the save was rejected to avoid overwriting it. Your " +
                        "text is still here — copy anything you need, then back " +
                        "out and re-open to get the latest version.",
                )
            },
            confirmButton = { TextButton(onClick = { conflict = false }) { Text("OK") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete note?") },
            text = { Text("This removes it for everyone in the notebook.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        if (repo.notes.deleteNote(flag, noteId)) onBack()
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}
