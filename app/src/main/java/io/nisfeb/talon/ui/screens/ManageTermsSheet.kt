package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.TalonApplication
import io.nisfeb.talon.ai.sanitizeTerm
import io.nisfeb.talon.data.WatchwordEntity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageTermsSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as TalonApplication
    val watchwords = app.watchwords
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    val terms by watchwords.terms.collectAsState()
    val backfilling by watchwords.backfilling.collectAsState()
    val hitCounts by remember {
        app.db.watchwords().streamHitCountsByTerm()
    }.collectAsState(initial = emptyList())
    val countsByTerm = remember(hitCounts) { hitCounts.associate { it.term to it.cnt } }

    val syncEnabled by app.watchwordsSyncEnabled.collectAsState()

    var draftText by remember { mutableStateOf("") }
    var draftNotify by remember { mutableStateOf(true) }
    var pendingDelete by remember { mutableStateOf<WatchwordEntity?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Watchwords",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )

            // Add row
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draftText,
                    onValueChange = { draftText = it },
                    placeholder = { Text("Add a watchword…") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Notify", style = MaterialTheme.typography.labelSmall)
                    Switch(checked = draftNotify, onCheckedChange = { draftNotify = it })
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val trimmed = draftText.trim()
                        if (trimmed.isNotEmpty()) {
                            scope.launch { watchwords.add(trimmed, draftNotify) }
                            draftText = ""
                        }
                    },
                    enabled = draftText.trim().isNotEmpty(),
                ) { Text("Add") }
            }

            // Collision warning when the typed term sanitizes to an existing key
            val typed = draftText.trim()
            if (syncEnabled && typed.isNotEmpty()) {
                val key = remember(typed) { sanitizeTerm(typed) }
                val collision = remember(typed, terms) {
                    terms.firstOrNull { sanitizeTerm(it.term) == key && it.term != typed }
                }
                if (collision != null) {
                    Text(
                        "This term shares a sync key with '${collision.term}'. Pick distinct words to avoid clobbering it on other devices.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (terms.isEmpty()) {
                Text(
                    "No watchwords yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                terms.forEach { term ->
                    TermRow(
                        term = term,
                        hitCount = countsByTerm[term.term] ?: 0,
                        backfilling = term.id in backfilling,
                        onNotifyChange = { on ->
                            scope.launch { watchwords.setNotify(term.id, on) }
                        },
                        onDelete = { pendingDelete = term },
                    )
                }
            }

            // Sync row
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Sync watchwords across devices",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        if (syncEnabled) "On — terms mirror to your ship's settings."
                        else "Off — terms stay on this device only.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = syncEnabled,
                    onCheckedChange = { app.setWatchwordsSyncEnabled(it) },
                )
            }

            Text(
                "Backfill scans messages cached on this device. Older history pages back-load as you scroll a chat.",
                style = MaterialTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    pendingDelete?.let { term ->
        val count = countsByTerm[term.term] ?: 0
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete '${term.term}'?") },
            text = {
                Text(
                    if (count > 0) "This clears its $count hit${if (count == 1) "" else "s"}."
                    else "No hits will be lost."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { watchwords.remove(term.id) }
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TermRow(
    term: WatchwordEntity,
    hitCount: Int,
    backfilling: Boolean,
    onNotifyChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            term.term,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (backfilling) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(14.dp).padding(end = 8.dp),
            )
        } else if (hitCount > 0) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    hitCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = term.notify,
            onCheckedChange = onNotifyChange,
        )
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}
