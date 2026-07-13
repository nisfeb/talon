package io.nisfeb.talon.ui.screens
import io.nisfeb.talon.util.nowMs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.ai.LoopSchedule
import io.nisfeb.talon.ai.LoopScheduler
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.LoopEntity
import io.nisfeb.talon.data.newGid
import io.nisfeb.talon.ui.MarkdownText
import io.nisfeb.talon.ui.isBackgroundSchedulingSupported
import io.nisfeb.talon.ui.shortRelativeTime
import io.nisfeb.talon.urbit.SettingsSync
import kotlinx.coroutines.delay
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
    // Standalone (full-screen) host: the list and a job's detail/editor
    // swap in place. In the assistant they live in the two panes instead.
    var openJob by remember { mutableStateOf<LoopEntity?>(null) }
    val job = openJob
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            job == null -> "Loops"
                            job.id == 0L -> "New loop"
                            else -> job.name
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (job != null) openJob = null else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            if (job != null) {
                LoopDetail(
                    db = db,
                    initialLoop = job,
                    scheduler = scheduler,
                    settingsSync = settingsSync,
                    onRunNow = onRunNow,
                    onClose = { openJob = null },
                )
            } else {
                LoopsList(
                    db = db,
                    scheduler = scheduler,
                    settingsSync = settingsSync,
                    selectedId = null,
                    onSelect = { openJob = it },
                    onNew = { openJob = blankLoop() },
                )
            }
        }
    }
}

/**
 * Compact loops roster — one row per job (name, interval, on/off, a small
 * last-run status). Selecting a row hands the job to the host, which shows
 * its detail/editor (right pane in the assistant, full-screen in
 * [LoopsScreen]). Empty state + the "New" affordance live here.
 */
@Composable
fun LoopsList(
    db: AppDatabase,
    scheduler: LoopScheduler,
    settingsSync: SettingsSync? = null,
    selectedId: Long? = null,
    onSelect: (LoopEntity) -> Unit,
    onNew: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val loopDao = remember(db) { db.loops() }
    // null = the first DB emission is still in flight. Rendering the empty
    // state during that frame flashes "no loops / add a loop" when switching
    // to the Jobs tab before the list loads — so wait for the real value.
    // remember the Flow — a DAO call returns a new instance each time, and
    // collectAsState keys on it, so an inline call re-queries per recomposition.
    val loops by remember(loopDao) { loopDao.stream() }.collectAsState(initial = null)
    val list = loops ?: return

    if (list.isEmpty()) {
        EmptyLoops(onAdd = onNew)
        return
    }
    LazyColumn(
        modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (isBackgroundSchedulingSupported) {
                        "Runs on your LLM key, on this device."
                    } else {
                        "Runs on your LLM key, on this device — only while Talon is open."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onNew) { Text("New") }
            }
        }
        items(list, key = { it.id }) { loop ->
            LoopListRow(
                db = db,
                loop = loop,
                selected = loop.id == selectedId,
                onClick = { onSelect(loop) },
                onToggleEnabled = { on ->
                    scope.launch {
                        loopDao.setEnabled(loop.id, on, nowMs())
                        // setEnabled is a partial UPDATE, so `loop` is stale for
                        // enabled/updatedAt — re-read the fresh row before pushing.
                        loopDao.get(loop.id)?.let { settingsSync?.pushLoop(it) }
                        scheduler.reschedule()
                    }
                },
            )
        }
    }
}

@Composable
private fun LoopListRow(
    db: AppDatabase,
    loop: LoopEntity,
    selected: Boolean,
    onClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
) {
    // Only the LATEST run for an at-a-glance status; the full history lives
    // in the detail pane, not crammed into the list.
    val latest by remember(loop.id) { db.loopRuns().streamForLoop(loop.id, 1) }
        .collectAsState(initial = emptyList())
    val run = latest.firstOrNull()
    Card(
        Modifier.fillMaxWidth().clickable { onClick() },
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    loop.name.ifBlank { "(unnamed)" },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(scheduleLabel(loop))
                        run?.let {
                            append("  ·  ")
                            append(if (it.ok) "✓ " else "⚠ ")
                            append(shortRelativeTime(it.ranAt, nowMs()))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (run?.ok == false) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = loop.enabled, onCheckedChange = onToggleEnabled)
        }
    }
}

/**
 * Full detail for one job — prompt, run controls, and the complete run
 * history — with an inline editor. Self-contained: owns the working copy of
 * the loop and its edit state (a new job, id 0, opens straight in the
 * editor). The host places this in the right pane (assistant) or full-screen
 * ([LoopsScreen]).
 */
@Composable
fun LoopDetail(
    db: AppDatabase,
    initialLoop: LoopEntity,
    scheduler: LoopScheduler,
    settingsSync: SettingsSync? = null,
    onRunNow: (Long) -> Unit,
    onClose: () -> Unit,
    // Render an in-pane back button (assistant right pane). Off when the host
    // supplies its own back chrome (LoopsScreen's app bar).
    showBack: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val loopDao = remember(db) { db.loops() }
    var loop by remember(initialLoop.id) { mutableStateOf(initialLoop) }
    var editing by remember(initialLoop.id) { mutableStateOf(initialLoop.id == 0L) }

    // "Run now" is fire-and-forget and takes a few seconds (an LLM call); it
    // lands a fresh run row + a notification when done. Track from the click
    // until that row appears (or a safety timeout) so the button visibly does
    // something instead of looking dead.
    var runStartedAt by remember(loop.id) { mutableStateOf<Long?>(null) }
    val latestRun by remember(loop.id) { db.loopRuns().streamForLoop(loop.id, 1) }
        .collectAsState(initial = emptyList())
    val latestRanAt = latestRun.firstOrNull()?.ranAt
    LaunchedEffect(latestRanAt) {
        val started = runStartedAt
        if (started != null && latestRanAt != null && latestRanAt >= started) runStartedAt = null
    }
    LaunchedEffect(runStartedAt) {
        if (runStartedAt != null) { delay(120_000); runStartedAt = null }
    }
    val running = runStartedAt != null

    if (editing) {
        Column(modifier.fillMaxSize()) {
            Text(
                if (loop.id == 0L) "New loop" else "Edit loop",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp),
            )
            LoopForm(
                modifier = Modifier.weight(1f),
                initial = loop,
                onCancel = { if (loop.id == 0L) onClose() else editing = false },
                onSave = { r ->
                    scope.launch {
                        val now = nowMs()
                        val row = if (loop.id == 0L) {
                            loop.copy(
                                gid = newGid(),
                                name = r.name, prompt = r.prompt,
                                intervalMinutes = r.intervalMinutes,
                                scheduleKind = r.scheduleKind,
                                atMinuteOfDay = r.atMinuteOfDay,
                                daysMask = r.daysMask,
                                writesAuthorized = r.writesAuthorized,
                                createdAt = now, updatedAt = now, lastRunAt = now,
                            )
                        } else {
                            // Merge the form onto the FRESH row, not the pane's
                            // snapshot — a background fire may have stamped
                            // lastRunAt (and the list pane may have toggled
                            // enabled) since this detail opened; writing the
                            // stale snapshot would revert those, and a reverted
                            // lastRunAt makes the loop immediately due again.
                            // Legacy loops predate sync (gid=""); stamp one on
                            // first edit so they start syncing.
                            val fresh = loopDao.get(loop.id) ?: loop
                            fresh.copy(
                                gid = fresh.gid.ifBlank { newGid() },
                                name = r.name, prompt = r.prompt,
                                intervalMinutes = r.intervalMinutes,
                                scheduleKind = r.scheduleKind,
                                atMinuteOfDay = r.atMinuteOfDay,
                                daysMask = r.daysMask,
                                writesAuthorized = r.writesAuthorized,
                                updatedAt = now,
                            )
                        }
                        val id = loopDao.upsert(row)
                        settingsSync?.pushLoop(row)
                        scheduler.reschedule()
                        // @Upsert returns the rowId on INSERT but -1 on UPDATE —
                        // adopt it only for a brand-new loop, or every action in
                        // this pane (Run now, history, Delete) targets id -1.
                        loop = if (row.id == 0L) row.copy(id = id) else row
                        editing = false
                    }
                },
            )
        }
    } else {
        Column(
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showBack) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text(
                        loop.name,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Text(loop.name, style = MaterialTheme.typography.titleLarge)
            }
            Text(
                scheduleLabel(loop) + if (loop.enabled) "" else " · paused",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(loop.prompt, style = MaterialTheme.typography.bodyMedium)
            // Write authorization is per-device and does NOT sync, so a job
            // created/authorized on another device arrives read-only here —
            // and silently can't post. Surface that, with a one-tap grant.
            if (loop.writesAuthorized) {
                Text(
                    "Authorized to act on your ship (send messages, poke agents).",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "Read-only on this device",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            "It can read your chats but can't send messages or act on your " +
                                "ship. The write grant is per-device and doesn't sync, so a job " +
                                "authorized on another device arrives read-only here. Enable it " +
                                "only for prompts you trust — it then acts unattended, with no " +
                                "confirmation.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        TextButton(onClick = {
                            scope.launch {
                                // Partial update — a whole-row upsert of the
                                // pane's snapshot would revert a lastRunAt
                                // stamped by a concurrent fire, making the
                                // loop immediately due for a duplicate,
                                // now write-authorized, run.
                                loopDao.setWritesAuthorized(loop.id, true)
                                scheduler.reschedule()
                                loop = loop.copy(writesAuthorized = true)
                            }
                        }) { Text("Authorize writes on this device") }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        runStartedAt = nowMs()
                        onRunNow(loop.id)
                    },
                    enabled = !running,
                ) {
                    if (running) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Running…")
                    } else {
                        Text("Run now")
                    }
                }
                TextButton(onClick = { editing = true }) { Text("Edit") }
                TextButton(onClick = {
                    scope.launch {
                        val gid = loop.gid
                        loopDao.delete(loop.id)
                        db.loopRuns().deleteForLoop(loop.id)
                        settingsSync?.deleteLoop(gid)
                        scheduler.reschedule()
                        onClose()
                    }
                }) { Text("Delete") }
            }
            HorizontalDivider()
            Text("Run history", style = MaterialTheme.typography.titleSmall)
            LoopRunHistory(db, loop.id)
        }
    }
}

@Composable
private fun LoopRunHistory(db: AppDatabase, loopId: Long) {
    val runs by remember(loopId) { db.loopRuns().streamForLoop(loopId, 20) }
        .collectAsState(initial = emptyList())
    if (runs.isEmpty()) {
        Text(
            "No runs yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val now = remember(runs) { nowMs() }
    // Selectable so the output (esp. an error message) can be copied.
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            runs.forEach { run ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        (if (run.ok) "✓ " else "⚠ ") + shortRelativeTime(run.ranAt, now),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (run.ok) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                    )
                    if (run.output.isNotBlank()) MarkdownText(run.output)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun LoopForm(
    initial: LoopEntity,
    onCancel: () -> Unit,
    onSave: (LoopFormResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf(initial.name) }
    var prompt by remember { mutableStateOf(initial.prompt) }
    var interval by remember { mutableStateOf(initial.intervalMinutes.takeIf { it > 0 } ?: 60) }
    var writesAuthorized by remember { mutableStateOf(initial.writesAuthorized) }
    var scheduleKind by remember {
        mutableStateOf(initial.scheduleKind.ifBlank { LoopSchedule.KIND_INTERVAL })
    }
    val timeState = rememberTimePickerState(
        initialHour = initial.atMinuteOfDay / 60,
        initialMinute = initial.atMinuteOfDay % 60,
        is24Hour = true,
    )
    var daysMask by remember { mutableStateOf(initial.daysMask) }

    // Scroll the whole form: a long prompt otherwise grows the field past
    // the bottom of the screen and pushes Save out of reach with no way to
    // get to it. imePadding keeps Save above the keyboard while editing.
    Column(
        modifier
            .fillMaxWidth()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
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
        Text("Schedule", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = scheduleKind == LoopSchedule.KIND_INTERVAL,
                onClick = { scheduleKind = LoopSchedule.KIND_INTERVAL },
                label = { Text("Every so often") },
            )
            FilterChip(
                selected = scheduleKind == LoopSchedule.KIND_WEEKLY,
                onClick = { scheduleKind = LoopSchedule.KIND_WEEKLY },
                label = { Text("At a set time") },
            )
        }
        if (scheduleKind == LoopSchedule.KIND_INTERVAL) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LoopSchedule.PRESET_MINUTES.forEach { m ->
                    FilterChip(
                        selected = interval == m,
                        onClick = { interval = m },
                        label = { Text(intervalLabel(m)) },
                    )
                }
            }
        } else {
            TimeInput(state = timeState)
            Text(
                "On these days (none = every day)",
                style = MaterialTheme.typography.labelMedium,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LoopSchedule.WEEKDAYS.forEach { (label, bit) ->
                    FilterChip(
                        selected = (daysMask and bit) != 0,
                        onClick = { daysMask = daysMask xor bit },
                        label = { Text(label) },
                    )
                }
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
                onClick = {
                    onSave(
                        LoopFormResult(
                            name = name.trim(),
                            prompt = prompt.trim(),
                            writesAuthorized = writesAuthorized,
                            scheduleKind = scheduleKind,
                            intervalMinutes = interval,
                            atMinuteOfDay = timeState.hour * 60 + timeState.minute,
                            daysMask = daysMask,
                        ),
                    )
                },
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

internal fun blankLoop() = LoopEntity(
    name = "", prompt = "", intervalMinutes = 60, createdAt = 0, updatedAt = 0,
)

/** Carries the editor's fields back to [LoopDetail.onSave] — beats a
 *  7-arg lambda now that a loop can be interval- or time-scheduled. */
private data class LoopFormResult(
    val name: String,
    val prompt: String,
    val writesAuthorized: Boolean,
    val scheduleKind: String,
    val intervalMinutes: Int,
    val atMinuteOfDay: Int,
    val daysMask: Int,
)

private fun scheduleLabel(loop: LoopEntity): String =
    if (loop.scheduleKind == LoopSchedule.KIND_WEEKLY) {
        val time = (loop.atMinuteOfDay / 60).toString().padStart(2, '0') + ":" +
            (loop.atMinuteOfDay % 60).toString().padStart(2, '0')
        val days = if (loop.daysMask == 0) "every day"
        else LoopSchedule.WEEKDAYS
            .filter { (_, bit) -> (loop.daysMask and bit) != 0 }
            .joinToString(", ") { it.first }
        "At $time · $days"
    } else {
        "Every ${intervalLabel(loop.intervalMinutes)}"
    }

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
