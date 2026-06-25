package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.ai.LoopSchedule
import io.nisfeb.talon.ai.LoopScheduler
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.LoopEntity
import io.nisfeb.talon.data.newGid
import io.nisfeb.talon.ui.MarkdownText
import io.nisfeb.talon.urbit.SettingsSync
import kotlinx.coroutines.launch

/**
 * User-defined Loops: saved prompts the assistant agent runs on a
 * schedule, headless. Pure CRUD over the local `loop` table — running and
 * (re)scheduling are delegated to injected hooks ([onRunNow], [scheduler])
 * so the agent + AlarmManager machinery stays in the platform leaf
 * (Android's Loops facade). Shown only where isLoopsSupported + an LLM key
 * are present (gated at the call site).
 *
 * @param onRunNow fire a one-off run of a loop now (result lands in its
 *   history + a notification); no-op where loops can't run headless.
 * @param scheduler re-armed after every add/edit/enable/delete.
 * @param settingsSync pushes loop *definitions* to %settings so they
 *   follow the ship across devices; lastRunAt + run history stay local.
 *   The DAO has no change hook, so the screen pushes directly. Null (and
 *   the no-op default impl) where cross-device sync isn't wired.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoopsScreen(
    db: AppDatabase,
    scheduler: LoopScheduler,
    onRunNow: (Long) -> Unit,
    onBack: () -> Unit,
    settingsSync: SettingsSync? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val loopDao = remember(db) { db.loops() }
    val loops by loopDao.stream().collectAsState(initial = emptyList())

    // null = list view; a LoopEntity (id 0 for new) = the add/edit form.
    var editing by remember { mutableStateOf<LoopEntity?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(if (editing == null) "Loops" else if (editing!!.id == 0L) "New loop" else "Edit loop") },
                navigationIcon = {
                    IconButton(onClick = { if (editing != null) editing = null else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (editing == null) {
                        IconButton(onClick = { editing = blankLoop() }) {
                            Icon(Icons.Filled.Add, contentDescription = "New loop")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val form = editing
            if (form != null) {
                LoopForm(
                    initial = form,
                    onCancel = { editing = null },
                    onSave = { name, prompt, interval, writes ->
                        scope.launch {
                            val now = System.currentTimeMillis()
                            val row = if (form.id == 0L) {
                                form.copy(
                                    gid = newGid(),
                                    name = name, prompt = prompt, intervalMinutes = interval,
                                    writesAuthorized = writes,
                                    createdAt = now, updatedAt = now, lastRunAt = now,
                                )
                            } else {
                                form.copy(
                                    // Legacy loops predate sync (gid=""); stamp one
                                    // on first edit so they start syncing.
                                    gid = form.gid.ifBlank { newGid() },
                                    name = name, prompt = prompt, intervalMinutes = interval,
                                    writesAuthorized = writes,
                                    updatedAt = now,
                                )
                            }
                            loopDao.upsert(row)
                            settingsSync?.pushLoop(row)
                            scheduler.reschedule()
                            editing = null
                        }
                    },
                )
            } else if (loops.isEmpty()) {
                EmptyLoops(onAdd = { editing = blankLoop() })
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(
                            "Loops run on your LLM key — each run uses tokens. " +
                                "They run on this device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(loops, key = { it.id }) { loop ->
                        LoopCard(
                            db = db,
                            loop = loop,
                            onToggleEnabled = { on ->
                                scope.launch {
                                    loopDao.setEnabled(loop.id, on, System.currentTimeMillis())
                                    // setEnabled is a partial UPDATE, so `loop` is
                                    // stale for enabled/updatedAt — re-read the fresh
                                    // row before pushing.
                                    loopDao.get(loop.id)?.let { settingsSync?.pushLoop(it) }
                                    scheduler.reschedule()
                                }
                            },
                            onRunNow = { onRunNow(loop.id) },
                            onEdit = { editing = loop },
                            onDelete = {
                                scope.launch {
                                    val gid = loop.gid
                                    loopDao.delete(loop.id)
                                    db.loopRuns().deleteForLoop(loop.id)
                                    settingsSync?.deleteLoop(gid)
                                    scheduler.reschedule()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoopCard(
    db: AppDatabase,
    loop: LoopEntity,
    onToggleEnabled: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(loop.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Every ${intervalLabel(loop.intervalMinutes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = loop.enabled, onCheckedChange = onToggleEnabled)
            }
            Text(
                loop.prompt,
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRunNow) { Text("Run now") }
                    TextButton(onClick = onEdit) { Text("Edit") }
                    TextButton(onClick = onDelete) { Text("Delete") }
                }
                ExpandedRuns(db, loop.id)
            }
        }
    }
}

@Composable
private fun ExpandedRuns(db: AppDatabase, loopId: Long) {
    val runs by remember(loopId) { db.loopRuns().streamForLoop(loopId, 20) }
        .collectAsState(initial = emptyList())
    if (runs.isEmpty()) {
        Text(
            "No runs yet.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        runs.forEach { run ->
            Text(
                (if (run.ok) "" else "⚠ ") + "run",
                style = MaterialTheme.typography.labelSmall,
                color = if (run.ok) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
            )
            MarkdownText(run.output)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoopForm(
    initial: LoopEntity,
    onCancel: () -> Unit,
    onSave: (name: String, prompt: String, intervalMinutes: Int, writesAuthorized: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var prompt by remember { mutableStateOf(initial.prompt) }
    var interval by remember { mutableStateOf(initial.intervalMinutes.takeIf { it > 0 } ?: 60) }
    var writesAuthorized by remember { mutableStateOf(initial.writesAuthorized) }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Name") },
            singleLine = true,
        )
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Prompt — what should this loop do?") },
            minLines = 3,
        )
        Text("How often", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LoopSchedule.PRESET_MINUTES.forEach { m ->
                FilterChip(
                    selected = interval == m,
                    onClick = { interval = m },
                    label = { Text(intervalLabel(m)) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Let this loop act on your ship", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (writesAuthorized) {
                        "On: it can send messages and change data unattended, " +
                            "with no confirmation. Only for prompts you trust."
                    } else {
                        "Off: read-only — it can look at your chats but not act."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (writesAuthorized) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = writesAuthorized, onCheckedChange = { writesAuthorized = it })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSave(name.trim(), prompt.trim(), interval, writesAuthorized) },
                enabled = name.isNotBlank() && prompt.isNotBlank(),
            ) { Text("Save") }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun EmptyLoops(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "No loops yet. A loop runs a prompt over your chats on a schedule " +
                "and notifies you with the result.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onAdd) { Text("Add a loop") }
    }
}

private fun blankLoop() = LoopEntity(
    name = "", prompt = "", intervalMinutes = 60, createdAt = 0, updatedAt = 0,
)

private fun intervalLabel(min: Int): String = when (min) {
    15 -> "15m"
    30 -> "30m"
    60 -> "1h"
    180 -> "3h"
    360 -> "6h"
    720 -> "12h"
    1440 -> "Daily"
    else -> if (min % 60 == 0) "${min / 60}h" else "${min}m"
}
