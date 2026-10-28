package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.calendar.CalendarRepo
import io.nisfeb.talon.calendar.CalendarTask
import io.nisfeb.talon.calendar.deleteBody
import io.nisfeb.talon.calendar.dueDate
import io.nisfeb.talon.calendar.dueIsDate
import io.nisfeb.talon.calendar.eveningReview
import io.nisfeb.talon.ui.TextButton
import io.nisfeb.talon.util.nowMs
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The evening review (orrery 130): what is late, what is due today and
 * what has no date, to organize before the day ends. Each one can be
 * given a day or none, ticked off, or dropped, with a few seconds to take
 * a drop back. Every write runs on the calendar's own scope, so leaving
 * the screen does not stop one halfway; a drop waits out its Undo there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EveningReviewScreen(
    calendar: CalendarRepo,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Where today and late are read: the device's, as orrery's counts follow the phone. */
    zone: TimeZone = TimeZone.currentSystemDefault(),
) {
    val scope = rememberCoroutineScope()
    val tasks by calendar.tasks.collectAsState()
    val calendars by calendar.calendars.collectAsState()
    val ticking by calendar.ticking.collectAsState()
    val clock by produceState(initialValue = nowMs()) {
        while (true) { value = nowMs(); delay(60_000L) }
    }
    LaunchedEffect(Unit) { calendar.refreshTasks() }
    var dropped by remember { mutableStateOf<Map<String, Pair<CalendarTask, Deferred<Unit>>>>(emptyMap()) }
    var problem by remember { mutableStateOf<String?>(null) }
    var dating by remember { mutableStateOf<CalendarTask?>(null) }
    val writable = remember(calendars) { calendar.writable().map { it.id }.toSet() }
    val shown = remember(tasks, writable, clock, ticking, dropped) {
        val open = tasks.orEmpty().filter { ticking[it.id] != true && it.id !in dropped }
        eveningReview(open, writable, clock, zone)
    }
    val today = remember(clock) { Instant.fromEpochMilliseconds(clock).toLocalDateTime(zone).date }

    fun dueOf(day: LocalDate?): Long? = day?.atStartOfDayIn(TimeZone.UTC)?.toEpochMilliseconds()
    fun move(t: CalendarTask, dueMs: Long?) {
        problem = null
        scope.launch { calendar.setTaskDue(t, dueMs)?.let { problem = it } }
    }
    fun done(t: CalendarTask) {
        problem = null
        scope.launch { if (!calendar.setDone(t.id, true)) problem = calendar.failedLine("\"${t.name}\" was not ticked off.") }
    }
    fun drop(t: CalendarTask) {
        problem = null
        // On the calendar's scope: the Undo's wait, and the write after it, outlive the screen.
        val going = calendar.behind {
            delay(UNDO_MS)
            val w = calendar.writeEvent(deleteBody(t.id))
            if (!w.ok) problem = w.why ?: calendar.failedLine("\"${t.name}\" was not dropped.")
        }
        dropped = dropped + (t.id to (t to going))
    }
    fun undo(id: String) {
        dropped[id]?.second?.cancel()
        dropped = dropped - id
    }

    Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            io.nisfeb.talon.ui.IconButton(tip = "Back", onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text("Evening review", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                Text(
                    when {
                        tasks == null -> "Reading your tasks…"
                        shown.isEmpty -> "Nothing to organize."
                        else -> shown.line
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
        problem?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp, 8.dp))
        }
        LazyColumn(Modifier.weight(1f)) {
            for ((heading, list) in listOf("Overdue" to shown.overdue, "Due today" to shown.today, "No date" to shown.undated)) {
                if (list.isEmpty()) continue
                item(key = "h:$heading") {
                    Text(
                        heading,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(list, key = { it.id }) { t ->
                    ReviewRow(
                        t, dueLabel(t, today, zone),
                        onDone = { done(t) },
                        onDay = { day -> move(t, dueOf(day)) },
                        onPick = { dating = t },
                        onDrop = { drop(t) },
                        today = today,
                    )
                }
            }
        }
        // Each drop waits here a few seconds, to be taken back.
        dropped.values.lastOrNull()?.let { (t, _) ->
            Surface(color = MaterialTheme.colorScheme.inverseSurface, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Dropped \"${t.name}\".", color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = { undo(t.id) }) { Text("Undo", color = MaterialTheme.colorScheme.inversePrimary) }
                }
            }
            LaunchedEffect(t.id) {
                dropped[t.id]?.second?.join()
                dropped = dropped - t.id
            }
        }
    }

    dating?.let { t ->
        val state = rememberDatePickerState(initialSelectedDateMillis = dueOf(t.dueDate(zone) ?: today))
        DatePickerDialog(
            onDismissRequest = { dating = null },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms -> move(t, dueOf(Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.UTC).date)) }
                    dating = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { dating = null }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}

/**
 * The way in from Home and Orrery: the review's counts, to tap. Nothing
 * when there is nothing to organize.
 */
@Composable
fun EveningReviewEntry(
    calendar: CalendarRepo,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    zone: TimeZone = TimeZone.currentSystemDefault(),
) {
    val tasks by calendar.tasks.collectAsState()
    val calendars by calendar.calendars.collectAsState()
    val clock by produceState(initialValue = nowMs()) {
        while (true) { value = nowMs(); delay(60_000L) }
    }
    val review = remember(tasks, calendars, clock) { eveningReview(tasks.orEmpty(), calendar.writable().map { it.id }.toSet(), clock, zone) }
    if (review.isEmpty) return
    Surface(
        onClick = onOpen,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Evening review", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                Text(review.line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

/** How long a drop waits for Undo before it is sent. */
internal const val UNDO_MS = 5_000L

/** "Yesterday", "Oct 27", or with a time "22:00" today and "Oct 27, 22:00" another day. */
internal fun dueLabel(t: CalendarTask, today: LocalDate, zone: TimeZone): String? {
    val day = t.dueDate(zone) ?: return null
    val date = when (day) {
        today -> "Today"
        today.plus(-1, DateTimeUnit.DAY) -> "Yesterday"
        else -> io.nisfeb.talon.ai.DictationCards.dayLabel(day, today, short = true)
    }
    if (t.dueIsDate) return date
    val at = io.nisfeb.talon.util.formatClock(t.dueMs!!, zone)
    return if (day == today) at else "$date, $at"
}

@Composable
private fun ReviewRow(
    t: CalendarTask,
    due: String?,
    onDone: () -> Unit,
    onDay: (LocalDate?) -> Unit,
    onPick: () -> Unit,
    onDrop: () -> Unit,
    today: LocalDate,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Checkbox(checked = false, onCheckedChange = { if (it) onDone() })
        Column(Modifier.weight(1f)) {
            Text(t.name.ifBlank { "(untitled)" }, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            due?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Box {
            TextButton(onClick = { menu = true }) { Text(if (due == null) "Set a day" else "Move") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Today") }, onClick = { menu = false; onDay(today) })
                DropdownMenuItem(text = { Text("Tomorrow") }, onClick = { menu = false; onDay(today.plus(1, DateTimeUnit.DAY)) })
                DropdownMenuItem(text = { Text("Next week") }, onClick = { menu = false; onDay(today.plus(7, DateTimeUnit.DAY)) })
                DropdownMenuItem(text = { Text("Pick a day…") }, onClick = { menu = false; onPick() })
                if (due != null) DropdownMenuItem(text = { Text("No date") }, onClick = { menu = false; onDay(null) })
            }
        }
        TextButton(onClick = onDrop) { Text("Drop") }
    }
}
