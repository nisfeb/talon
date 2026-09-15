package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.calendar.CalendarAvailability
import io.nisfeb.talon.calendar.CalendarInfo
import io.nisfeb.talon.calendar.CalendarRepo
import io.nisfeb.talon.calendar.CalendarRow
import io.nisfeb.talon.calendar.CalendarTask
import io.nisfeb.talon.calendar.Shares
import io.nisfeb.talon.urbit.isValidPatp
import io.nisfeb.talon.calendar.dueDate
import io.nisfeb.talon.calendar.taskOrder
import io.nisfeb.talon.calendar.EventCat
import io.nisfeb.talon.calendar.EventDraft
import io.nisfeb.talon.calendar.EditScope
import io.nisfeb.talon.calendar.ORDINALS
import io.nisfeb.talon.calendar.Repeat
import io.nisfeb.talon.calendar.followingBody
import io.nisfeb.talon.calendar.onlyBody
import io.nisfeb.talon.calendar.daysOf
import io.nisfeb.talon.calendar.draftFromEvent
import io.nisfeb.talon.calendar.eventBody
import io.nisfeb.talon.calendar.monthGrid
import io.nisfeb.talon.util.nowMs
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The calendar section: a month with its events, the day picked out
 * of it, and an editor for any of them. Reads the ship's calendar
 * through [CalendarRepo]; writes are the same pokes the calendar's
 * own page sends. Several calendars at once, each with its colour,
 * any of which can be switched off on this device.
 *
 * One composable for both shells: [onBack] is the phone's arrow and
 * null in a desktop pane.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    repo: CalendarRepo,
    twentyFourHour: Boolean,
    onBack: (() -> Unit)?,
    /** Opens the calendar's own page, for what only it has: sharing
     *  over CalDAV, following, Google, ICS feeds. Null where the host
     *  cannot show it. */
    onOpenWebSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val availability by repo.availability.collectAsState()
    val allTags by repo.tags.collectAsState()
    var tagFilter by remember { mutableStateOf<String?>(null) }
    val rows by repo.rangeRows.collectAsState()
    val tasks by repo.tasks.collectAsState()
    var showTasks by remember { mutableStateOf(false) }
    val calendars by repo.calendars.collectAsState()
    val shares by repo.shares.collectAsState()
    val readOnly = shares?.readOnly.orEmpty()
    val hidden by repo.hidden.collectAsState()
    val zoneId by repo.zone.collectAsState()
    val error by repo.error.collectAsState()
    val notice by repo.notice.collectAsState()
    val zone = zoneFor(zoneId)
    val scope = rememberCoroutineScope()

    val today = remember { Instant.fromEpochMilliseconds(nowMs()).toLocalDateTime(TimeZone.currentSystemDefault()).date }
    var year by remember { mutableStateOf(today.year) }
    var month by remember { mutableStateOf(today.monthNumber) }
    var selected by remember { mutableStateOf(today) }
    var editing by remember { mutableStateOf<Pair<String?, EventDraft>?>(null) }
    /** The row being looked at; editing starts from here. */
    var viewing by remember { mutableStateOf<CalendarRow?>(null) }
    var editingIdx by remember { mutableStateOf<Int?>(null) }
    var editingStartMs by remember { mutableStateOf<Long?>(null) }
    var zones by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(availability) { if (availability == CalendarAvailability.PRESENT) zones = repo.zones() }
    var managing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    val grid = remember(year, month) { monthGrid(year, month) }
    LaunchedEffect(year, month, zoneId, availability) {
        if (availability != CalendarAvailability.PRESENT) return@LaunchedEffect
        val from = grid.first().atTime(0, 0).toInstant(zone).toEpochMilliseconds()
        val to = grid.last().plus(1, DateTimeUnit.DAY).atTime(0, 0).toInstant(zone).toEpochMilliseconds()
        repo.loadRange(from, to)
    }
    // A tick shows at once and holds until the calendar answers; the
    // rows only learn of it on the refresh after the poke.
    var pendingTicks by remember { mutableStateOf(mapOf<String, Boolean>()) }
    val visible = remember(rows, hidden, tagFilter, pendingTicks) {
        rows.orEmpty().filter { it.cal !in hidden && (tagFilter == null || tagFilter in it.tags) }
            .map { r -> pendingTicks[r.id]?.let { r.copy(done = it) } ?: r }
    }
    val byDay = remember(visible, zoneId) {
        val m = HashMap<LocalDate, MutableList<CalendarRow>>()
        visible.forEach { r -> daysOf(r, zone).forEach { d -> m.getOrPut(d) { mutableListOf() }.add(r) } }
        m
    }
    val calColors = remember(calendars) { calendars.associate { it.id to it.color } }
    fun colourOf(r: CalendarRow) = calendarHexColor(r.color ?: calColors[r.cal])

    val readOnlyNote = "That calendar is shared with you read-only; its host makes the changes."
    fun openNew() {
        editing = null to EventDraft(date = selected, cal = calendars.firstOrNull { it.id !in hidden && it.id !in readOnly }?.id)
        editingIdx = null
        editingStartMs = null
    }
    fun openById(id: String, idx: Int?, startMs: Long?) {
        scope.launch {
            val json = repo.eventDetail(id)
            val d = json?.let { draftFromEvent(it, selected) }
            if (d == null) { status = "That event could not be read for editing."; return@launch }
            editing = id to d
            editingIdx = idx
            editingStartMs = startMs
        }
    }
    fun openExisting(r: CalendarRow) { if (r.cal in readOnly) status = readOnlyNote else openById(r.id, r.idx, r.l) }
    fun view(r: CalendarRow) { viewing = r }
    fun tick(id: String, done: Boolean) {
        pendingTicks = pendingTicks + (id to done)
        scope.launch {
            if (!repo.setDone(id, done)) status = "The ship did not take the change."
            pendingTicks = pendingTicks - id
        }
    }
    /** Closes the editor and says what is happening; the calendar's
     *  answer, and the reads after it, take seconds on a busy ship. */
    fun act(doing: String, failed: String, body: suspend () -> Boolean) {
        editing = null
        status = doing
        scope.launch { status = if (body()) null else failed }
    }

    Column(modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            io.nisfeb.talon.ui.NavIcon(onBack = onBack)
            Text(
                if (showTasks) "Tasks" else "${MonthNames.ENGLISH_FULL.names[month - 1]} $year",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            if (!showTasks) {
                IconButton(onClick = { if (month == 1) { month = 12; year -= 1 } else month -= 1 }) {
                    Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
                }
                TextButton(onClick = { year = today.year; month = today.monthNumber; selected = today }) { Text("Today") }
                IconButton(onClick = { if (month == 12) { month = 1; year += 1 } else month += 1 }) {
                    Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "Next month")
                }
            }
            IconButton(onClick = { showTasks = !showTasks }) {
                if (showTasks) Icon(Icons.Filled.CalendarMonth, contentDescription = "Month")
                else Icon(Icons.Filled.Checklist, contentDescription = "Tasks")
            }
            IconButton(onClick = { managing = true }) {
                Icon(Icons.Filled.Tune, contentDescription = "Calendars")
            }
        }
        when (availability) {
            CalendarAvailability.ABSENT -> {
                Text(
                    "This ship has no calendar yet. The Today widget on the home page can install it.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
                return@Column
            }
            CalendarAvailability.SIGNED_OUT -> {
                Text("Signed out of the ship.", modifier = Modifier.padding(16.dp))
                return@Column
            }
            else -> Unit
        }
        if (calendars.size > 1) {
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(calendars, key = { it.id }) { c ->
                    FilterChip(
                        selected = c.id !in hidden,
                        onClick = { repo.setHidden(c.id, c.id !in hidden) },
                        label = { Text(c.name.ifBlank { c.id }) },
                        leadingIcon = {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(calendarHexColor(c.color) ?: MaterialTheme.colorScheme.primary))
                        },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        if (allTags.isNotEmpty()) {
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item { FilterChip(selected = tagFilter == null, onClick = { tagFilter = null }, label = { Text("All tags") }) }
                items(allTags, key = { it }) { t ->
                    FilterChip(selected = tagFilter == t, onClick = { tagFilter = if (tagFilter == t) null else t }, label = { Text("#$t") })
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        if (showTasks) {
            TasksView(
                tasks = tasks?.filter { it.cal !in hidden && (tagFilter == null || tagFilter in it.tags) }
                    ?.map { t -> pendingTicks[t.id]?.let { t.copy(done = it) } ?: t },
                today = today,
                calendars = calendars.filter { it.id !in hidden && it.id !in readOnly },
                readOnly = readOnly,
                colourOf = { t -> calendarHexColor(t.color ?: calColors[t.cal]) },
                status = status ?: error,
                onTick = ::tick,
                onOpen = { t ->
                    // The row's day is midnight in the calendar's zone, as the window feed has it.
                    val dayMs = t.dueDate()?.atTime(0, 0)?.toInstant(zone)?.toEpochMilliseconds() ?: 0L
                    view(CalendarRow(id = t.id, cal = t.cal, meta = t.meta, cat = "todo", kind = "todo", all = true, l = dayMs, r = dayMs, done = t.done))
                },
                onAdd = { name, due, cal ->
                    val d = EventDraft(name = name, cat = EventCat.TODO, date = due ?: today, due = due, cal = cal, tags = listOfNotNull(tagFilter))
                    act("Adding…", "The ship did not take the task.") { repo.poke(eventBody(d)) }
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach {
                Text(
                    it, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                )
            }
        }
        grid.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                week.forEach { d ->
                    val inMonth = d.monthNumber == month
                    val isSel = d == selected
                    val dayRows = byDay[d].orEmpty()
                    Column(
                        Modifier
                            .weight(1f)
                            .aspectRatio(0.9f)
                            .padding(1.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                when {
                                    isSel -> MaterialTheme.colorScheme.primaryContainer
                                    d == today -> MaterialTheme.colorScheme.surfaceVariant
                                    else -> Color.Transparent
                                },
                            )
                            .clickable { selected = d },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "${d.dayOfMonth}",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (d == today) FontWeight.SemiBold else FontWeight.Normal,
                            ),
                            color = if (inMonth) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        dayRows.take(3).forEach { r ->
                            Box(
                                Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 1.dp)
                                    .height(4.dp).clip(RoundedCornerShape(2.dp))
                                    .background(colourOf(r) ?: MaterialTheme.colorScheme.primary),
                            )
                        }
                        if (dayRows.size > 3) {
                            Text("+${dayRows.size - 3}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 4.dp))
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${d3(selected.dayOfWeek)} ${selected.dayOfMonth} ${MonthNames.ENGLISH_ABBREVIATED.names[selected.monthNumber - 1]}",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = ::openNew) { Icon(Icons.Filled.Add, contentDescription = "New event") }
        }
        status?.let {
            Text(
                it, style = MaterialTheme.typography.bodySmall,
                color = if (it.endsWith("…")) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
        }
        notice?.let {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { repo.clearNotice() }) { Text("OK") }
            }
        }
        val dayRows = byDay[selected].orEmpty()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (rows == null) {
                Text("Looking…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
            } else if (dayRows.isEmpty()) {
                Text("Nothing on this day.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(dayRows, key = { "${it.id}/${it.idx}" }) { r ->
                        Row(
                            Modifier.fillMaxWidth().clickable { view(r) }.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (r.isTask) {
                                Checkbox(checked = r.done, onCheckedChange = { tick(r.id, it) }, enabled = r.cal !in readOnly, modifier = Modifier.size(24.dp))
                            }
                            Box(Modifier.size(10.dp).clip(CircleShape).background(colourOf(r) ?: MaterialTheme.colorScheme.primary))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    r.name.ifBlank { "(untitled)" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    textDecoration = if (r.done) TextDecoration.LineThrough else null,
                                )
                                val line = listOf(r.location.ifBlank { r.note }, r.tags.joinToString(" ") { "#$it" })
                                    .filter { it.isNotBlank() }.joinToString(" · ")
                                if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(spanLabel(r, selected, zone, twentyFourHour), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider(Modifier.padding(start = 36.dp))
                    }
                }
            }
            FloatingActionButton(onClick = ::openNew, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                Icon(Icons.Filled.Add, contentDescription = "New event")
            }
        }
    }

    viewing?.let { r ->
        // Deleting asks once, here in the details, and closes on the tap.
        var confirmDelete by remember(r.id) { mutableStateOf(false) }
        val calName = calendars.firstOrNull { it.id == r.cal }?.let { it.name.ifBlank { it.id } }
        val readOnlyHere = r.cal in readOnly
        AlertDialog(
            onDismissRequest = { viewing = null },
            title = { Text(r.name.ifBlank { "(untitled)" }) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val whenText = when {
                        r.isTask -> (r.dueLabel(zone) ?: "No due date") + if (r.done) " · done" else ""
                        else -> "${d3(selected.dayOfWeek)} ${selected.dayOfMonth} ${MonthNames.ENGLISH_ABBREVIATED.names[selected.monthNumber - 1]} · ${spanLabel(r, selected, zone, twentyFourHour)}" +
                            if (r.repeats) " · repeats ${r.kind}" else ""
                    }
                    Text(whenText, style = MaterialTheme.typography.bodyMedium)
                    if (r.location.isNotBlank()) Text(r.location, style = MaterialTheme.typography.bodyMedium)
                    if (r.note.isNotBlank()) Text(r.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (r.tags.isNotEmpty()) Text(r.tags.joinToString(" ") { "#$it" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(colourOf(r) ?: MaterialTheme.colorScheme.primary))
                        Text(
                            (calName ?: r.cal) + if (readOnlyHere) " · shared with you, read-only" else "",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!readOnlyHere) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (r.isTask) TextButton(onClick = { tick(r.id, !r.done); viewing = null }) { Text(if (r.done) "Reopen" else "Done") }
                            if (r.repeats) TextButton(onClick = {
                                val idx = r.idx
                                act("Skipping this one…", "The ship did not skip it.") {
                                    repo.poke(buildJsonObject { put("action", "skip-event"); put("id", r.id); put("idx", idx) })
                                }
                                viewing = null
                            }) { Text("Skip this one") }
                            if (confirmDelete) {
                                TextButton(onClick = {
                                    act(if (r.repeats) "Deleting the series…" else "Deleting…", "The ship did not delete \"${r.name}\"; it is still there.") {
                                        repo.poke(buildJsonObject { put("action", "del-event"); put("id", r.id) })
                                    }
                                    viewing = null
                                }) { Text(if (r.repeats) "Delete the whole series" else "Yes, delete", color = MaterialTheme.colorScheme.error) }
                                TextButton(onClick = { confirmDelete = false }) { Text("Keep") }
                            } else {
                                TextButton(onClick = { confirmDelete = true }) { Text(if (r.repeats) "Delete series" else "Delete") }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (!readOnlyHere) TextButton(onClick = { viewing = null; openById(r.id, r.idx.takeIf { !r.isTask }, r.l.takeIf { !r.isTask }) }) { Text("Edit") }
            },
            dismissButton = { TextButton(onClick = { viewing = null }) { Text("Close") } },
        )
    }
    editing?.let { (id, draft) ->
        EventEditor(
            initial = draft,
            existing = id != null,
            recurringOccurrence = id != null && draft.repeats && editingIdx != null,
            calendars = calendars.filter { it.id !in readOnly },
            zones = zones,
            twentyFourHour = twentyFourHour,
            onDismiss = { editing = null },
            onSave = { d, editScope ->
                val idx = editingIdx
                val occurrence = editingStartMs?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(zone) }
                act("Saving…", "The ship did not take the change; \"${d.name.trim()}\" is as it was.") {
                    when {
                        id == null || editScope == EditScope.ALL || idx == null || occurrence == null ->
                            repo.poke(eventBody(d, id))
                        // The page's own two steps: end or skip the old, then add.
                        editScope == EditScope.FOLLOWING ->
                            repo.poke(buildJsonObject { put("action", "cap-event"); put("id", id); put("dom", idx) }) &&
                                repo.poke(followingBody(d, occurrence))
                        else ->
                            repo.poke(buildJsonObject { put("action", "skip-event"); put("id", id); put("idx", idx) }) &&
                                repo.poke(onlyBody(d, occurrence))
                    }
                }
            },
            onDelete = if (id == null) null else {
                {
                    act(if (draft.repeats) "Deleting the series…" else "Deleting…", "The ship did not delete \"${draft.name.trim()}\"; it is still there.") {
                        repo.poke(buildJsonObject { put("action", "del-event"); put("id", id) })
                    }
                }
            },
            onSkip = if (id == null || editingIdx == null) null else {
                {
                    val idx = editingIdx!!
                    act("Skipping this one…", "The ship did not skip it.") {
                        repo.poke(buildJsonObject { put("action", "skip-event"); put("id", id); put("idx", idx) })
                    }
                }
            },
        )
    }
    if (managing) {
        var deviceZone by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) { deviceZone = repo.deviceZone() }
        CalendarsDialog(
            calendars = calendars,
            shares = shares,
            zone = zoneId,
            deviceZone = deviceZone,
            onSetZone = { z -> act("Setting the zone…", "The ship did not take the zone.") { repo.setZone(z) } },
            onDismiss = { managing = false },
            onMakeLocal = { id -> scope.launch { if (!repo.makeLocal(id)) status = "The ship would not make that calendar local." } },
            onOpenWebSettings = onOpenWebSettings,
            onShare = { id, ship, edit ->
                scope.launch {
                    status = when (repo.share(id, ship, edit)) {
                        null -> "The ship would not share that calendar."
                        false -> "$ship could not be reached (down, or no calendar there yet). The share is recorded; share again once it is up to send the offer."
                        true -> null
                    }
                }
            },
            onRevoke = { id, ship -> scope.launch { repo.revoke(id, ship) } },
            onAccept = { key -> scope.launch { if (!repo.accept(key)) status = "The ship would not accept that offer." } },
            onDecline = { key -> scope.launch { repo.decline(key) } },
            onSync = { scope.launch { repo.syncShares() } },
            onAdd = { name, colour ->
                scope.launch {
                    val id = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "cal" }
                    repo.poke(buildJsonObject { put("action", "add-calendar"); put("id", id); put("name", name); put("color", colour) })
                }
            },
            onEdit = { id, name, colour ->
                scope.launch { repo.poke(buildJsonObject { put("action", "edit-calendar"); put("id", id); put("name", name); put("color", colour) }) }
            },
            onDelete = { id -> scope.launch { repo.poke(buildJsonObject { put("action", "del-calendar"); put("id", id) }) } },
        )
    }
}

private fun d3(d: DayOfWeek) = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[d.isoDayNumber - 1]

/** A task row's due day, or null when it has none. */
private fun CalendarRow.dueLabel(zone: TimeZone): String? {
    if (l == 0L) return null
    val d = Instant.fromEpochMilliseconds(l).toLocalDateTime(zone).date
    return "Due ${d.dayOfMonth} ${MonthNames.ENGLISH_ABBREVIATED.names[d.monthNumber - 1]}"
}

/** When a row is, on the day being looked at. */
private fun spanLabel(r: CalendarRow, day: LocalDate, zone: TimeZone, twentyFourHour: Boolean): String {
    if (r.isTask) return if (r.done) "Done" else "Due"
    if (r.all) return "All day"
    val s = Instant.fromEpochMilliseconds(r.l).toLocalDateTime(zone)
    val e = Instant.fromEpochMilliseconds(r.r).toLocalDateTime(zone)
    fun t(x: kotlinx.datetime.LocalDateTime) = io.nisfeb.talon.ui.SkyClock.clockLabel(x.hour * 60 + x.minute, twentyFourHour)
    return when {
        s.date == day && e.date == day -> "${t(s)}–${t(e)}"
        s.date == day -> "from ${t(s)}"
        e.date == day -> "until ${t(e)}"
        else -> "All day"
    }
}

/** The event form. Saves the whole series; a single occurrence can be skipped. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EventEditor(
    initial: EventDraft,
    existing: Boolean,
    recurringOccurrence: Boolean,
    calendars: List<CalendarInfo>,
    zones: List<String>,
    twentyFourHour: Boolean,
    onDismiss: () -> Unit,
    onSave: (EventDraft, EditScope) -> Unit,
    onDelete: (() -> Unit)?,
    onSkip: (() -> Unit)?,
) {
    var d by remember { mutableStateOf(initial) }
    var editScope by remember { mutableStateOf(EditScope.ALL) }
    var zoneText by remember { mutableStateOf(initial.zone.orEmpty()) }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingUntil by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val time = rememberTimePickerState(initialHour = initial.minuteOfDay / 60, initialMinute = initial.minuteOfDay % 60, is24Hour = twentyFourHour)
    val fieldFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (d.cat == EventCat.TODO) (if (existing) "Edit task" else "New task") else if (existing) "Edit event" else "New event") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = d.name, onValueChange = { d = d.copy(name = it) }, label = { Text("Name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus),
                )
                if (calendars.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        calendars.forEach { c ->
                            FilterChip(selected = d.cal == c.id, onClick = { d = d.copy(cal = c.id) }, label = { Text(c.name.ifBlank { c.id }) })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EventCat.entries.forEach { c ->
                        FilterChip(selected = d.cat == c, onClick = { d = d.copy(cat = c) }, label = { Text(c.label) })
                    }
                }
                if (d.cat == EventCat.TODO) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Due", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { pickingDate = true }) { Text(d.due?.toString() ?: "no date") }
                        if (d.due != null) TextButton(onClick = { d = d.copy(due = null) }) { Text("clear") }
                    }
                    FilterChip(selected = d.done, onClick = { d = d.copy(done = !d.done) }, label = { Text(if (d.done) "Done" else "To do") })
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (d.cat == EventCat.DATE) "Date (the year is ignored)" else "Date", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { pickingDate = true }) { Text(d.date.toString()) }
                    }
                }
                if (d.cat == EventCat.TIMED) {
                    TimeInput(state = time)
                    OutlinedTextField(
                        value = d.durMin.toString(), onValueChange = { v -> v.toIntOrNull()?.let { d = d.copy(durMin = it) } },
                        label = { Text("Minutes") }, singleLine = true,
                    )
                }
                if (d.cat == EventCat.ALLDAY) {
                    OutlinedTextField(
                        value = d.spanDays.toString(), onValueChange = { v -> v.toIntOrNull()?.let { d = d.copy(spanDays = it) } },
                        label = { Text("Days") }, singleLine = true,
                    )
                }
                if (d.cat == EventCat.TIMED) {
                    // The zone the times are in: the calendar's unless named.
                    OutlinedTextField(
                        value = zoneText,
                        onValueChange = { zoneText = it; d = d.copy(zone = it.trim().takeIf { z -> z.isNotEmpty() && z in zones }) },
                        label = { Text("Zone") }, placeholder = { Text("the calendar's own") }, singleLine = true,
                        isError = zoneText.isNotBlank() && zoneText.trim() !in zones,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val q = zoneText.trim()
                    if (q.length >= 2 && q !in zones) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            zones.filter { it.contains(q, ignoreCase = true) }.take(3).forEach { z ->
                                FilterChip(selected = false, onClick = { zoneText = z; d = d.copy(zone = z) }, label = { Text(z) })
                            }
                        }
                    }
                }
                if (d.cat == EventCat.TIMED || d.cat == EventCat.ALLDAY) if (d.rawKind != null) {
                    Text(
                        "Repeats by an imported rule (${d.rawKind}), kept as it is. Everything else here can change.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if ((d.cat == EventCat.TIMED || d.cat == EventCat.ALLDAY) && d.rawKind == null) {
                    Text("Repeats", style = MaterialTheme.typography.labelMedium)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        Repeat.entries.forEach { r ->
                            FilterChip(selected = d.repeat == r, onClick = { d = d.copy(repeat = r) }, label = { Text(r.label) })
                        }
                    }
                    if (d.repeat == Repeat.MONTHLY_NTH) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            ORDINALS.forEach { o ->
                                FilterChip(selected = d.ordinal == o, onClick = { d = d.copy(ordinal = o) }, label = { Text(o) })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            DayOfWeek.entries.forEach { w ->
                                FilterChip(selected = (d.nthDay ?: d.date.dayOfWeek) == w, onClick = { d = d.copy(nthDay = w) }, label = { Text(d3(w).take(2)) })
                            }
                        }
                    }
                    if (d.repeat == Repeat.EVERY) {
                        OutlinedTextField(
                            value = d.periodMin.toString(), onValueChange = { v -> v.toIntOrNull()?.let { d = d.copy(periodMin = it) } },
                            label = { Text("Every, in minutes") }, singleLine = true,
                        )
                    }
                    if (d.repeat == Repeat.WEEKLY) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            DayOfWeek.entries.forEach { w ->
                                FilterChip(
                                    selected = w in d.weekdays,
                                    onClick = { d = d.copy(weekdays = if (w in d.weekdays) d.weekdays - w else d.weekdays + w) },
                                    label = { Text(d3(w).take(2)) },
                                )
                            }
                        }
                    }
                    if (d.repeat != Repeat.ONCE) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = if (d.count == 0) "" else d.count.toString(),
                                onValueChange = { v -> d = d.copy(count = v.toIntOrNull() ?: 0) },
                                label = { Text("Times") }, placeholder = { Text("no limit") }, singleLine = true,
                                modifier = Modifier.width(110.dp),
                            )
                            Text("or until", style = MaterialTheme.typography.labelMedium)
                            TextButton(onClick = { pickingUntil = true }) { Text(d.until?.toString() ?: "never") }
                            if (d.until != null) TextButton(onClick = { d = d.copy(until = null) }) { Text("clear") }
                        }
                    }
                }
                OutlinedTextField(value = d.location, onValueChange = { d = d.copy(location = it) }, label = { Text("Place") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = d.note, onValueChange = { d = d.copy(note = it) }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                var tagText by remember { mutableStateOf(d.tags.joinToString(", ")) }
                OutlinedTextField(
                    value = tagText,
                    onValueChange = { tagText = it; d = d.copy(tags = io.nisfeb.talon.calendar.parseTags(it)) },
                    label = { Text("Tags, comma separated") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                if (recurringOccurrence) {
                    Text("This change applies to", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        EditScope.entries.forEach { s ->
                            FilterChip(selected = editScope == s, onClick = { editScope = s }, label = { Text(s.label) })
                        }
                    }
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (onDelete != null || onSkip != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (recurringOccurrence && onSkip != null) TextButton(onClick = onSkip) { Text("Skip this one") }
                        if (onDelete != null) TextButton(onClick = onDelete) { Text(if (recurringOccurrence) "Delete series" else "Delete") }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val ready = d.copy(minuteOfDay = time.hour * 60 + time.minute)
                problem = when {
                    ready.name.isBlank() -> "A name is needed."
                    ready.repeats && ready.rawKind == null && ready.repeat == Repeat.WEEKLY && ready.weekdays.isEmpty() -> "Pick the weekdays."
                    ready.cat == EventCat.TIMED && zoneText.isNotBlank() && zoneText.trim() !in zones -> "That zone is not one the calendar knows."
                    else -> null
                }
                if (problem == null) onSave(ready, editScope)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (pickingDate || pickingUntil) {
        val forUntil = pickingUntil
        val start = (if (forUntil) d.until else if (d.cat == EventCat.TODO) d.due else d.date) ?: d.date
        val state = rememberDatePickerState(initialSelectedDateMillis = start.atTime(0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds())
        DatePickerDialog(
            onDismissRequest = { pickingDate = false; pickingUntil = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val picked = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.UTC).date
                        d = when {
                            forUntil -> d.copy(until = picked, count = 0)
                            d.cat == EventCat.TODO -> d.copy(due = picked, date = picked)
                            else -> d.copy(date = picked)
                        }
                    }
                    pickingDate = false; pickingUntil = false
                }) { Text("OK") }
            },
        ) { DatePicker(state = state) }
    }
}

/** The calendars themselves: add one, rename or recolour, delete. */
@Composable
private fun CalendarsDialog(
    calendars: List<CalendarInfo>,
    /** Null on a calendar too old to share with ships. */
    shares: Shares?,
    /** The calendar's own zone, null for none; the device's, if the calendar knows it. */
    zone: String?,
    deviceZone: String?,
    onSetZone: (String) -> Unit,
    onDismiss: () -> Unit,
    onMakeLocal: (id: String) -> Unit,
    onOpenWebSettings: (() -> Unit)?,
    onShare: (id: String, ship: String, edit: Boolean) -> Unit,
    onRevoke: (id: String, ship: String) -> Unit,
    onAccept: (key: String) -> Unit,
    onDecline: (key: String) -> Unit,
    onSync: () -> Unit,
    onAdd: (name: String, colour: String) -> Unit,
    onEdit: (id: String, name: String, colour: String) -> Unit,
    onDelete: (id: String) -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    var newColour by remember { mutableStateOf("#1e3a5f") }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editName by remember { mutableStateOf("") }
    var editColour by remember { mutableStateOf("") }
    var shareShip by remember { mutableStateOf("") }
    var shareEdit by remember { mutableStateOf(false) }
    // The share whose Revoke was tapped once; a second tap revokes.
    var revoking by remember { mutableStateOf<Pair<String, String>?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Calendars") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!shares?.offers.isNullOrEmpty()) {
                    Text("Offered to you", style = MaterialTheme.typography.labelMedium)
                    shares!!.offers.forEach { (key, o) ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(calendarHexColor(o.color) ?: MaterialTheme.colorScheme.primary))
                            Column(Modifier.weight(1f)) {
                                Text(o.name.ifBlank { o.cal }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("from ${o.host} · ${if (o.mode == "edit") "read and edit" else "read only"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { onAccept(key) }) { Text("Accept") }
                            TextButton(onClick = { onDecline(key) }) { Text("Decline") }
                        }
                    }
                    HorizontalDivider()
                }
                calendars.forEach { c ->
                    if (editingId == c.id) {
                        OutlinedTextField(value = editName, onValueChange = { editName = it }, label = { Text("Name") }, singleLine = true)
                        OutlinedTextField(value = editColour, onValueChange = { editColour = it }, label = { Text("Colour, #rrggbb") }, singleLine = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { onEdit(c.id, editName, editColour); editingId = null }) { Text("Save") }
                            TextButton(onClick = { editingId = null }) { Text("Cancel") }
                            if (c.id != "default" && c.kind == "local") TextButton(onClick = { onDelete(c.id); editingId = null }) { Text("Delete") }
                        }
                        if (c.kind != "local") {
                            Text(
                                if (c.kind == "ship") "Shared with you by its host; it is pulled every few minutes. Make local keeps a copy of your own and stops the sync."
                                else "Followed calendars sync both ways. Make local stops the sync and keeps everything in it; the source is left alone.",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = { onMakeLocal(c.id); editingId = null }) { Text("Make local") }
                        }
                        if (c.kind == "local" && shares != null) {
                            // Who sees this calendar, and a line to add a ship.
                            shares.shares[c.id].orEmpty().forEach { (ship, mode) ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("$ship · ${if (mode == "edit") "can edit" else "read only"}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                    if (revoking == c.id to ship) {
                                        Text("Their copy stays with them; it just stops syncing.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                        TextButton(onClick = { onRevoke(c.id, ship); revoking = null }) { Text("Revoke", color = MaterialTheme.colorScheme.error) }
                                        TextButton(onClick = { revoking = null }) { Text("Keep") }
                                    } else {
                                        TextButton(onClick = { revoking = c.id to ship }) { Text("Revoke") }
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = shareShip, onValueChange = { shareShip = it }, label = { Text("Share with a ship") }, placeholder = { Text("~sampel-palnet") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            val ship = shareShip.trim().let { if (it.isNotEmpty() && !it.startsWith("~")) "~$it" else it }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(selected = !shareEdit, onClick = { shareEdit = false }, label = { Text("Read only") })
                                FilterChip(selected = shareEdit, onClick = { shareEdit = true }, label = { Text("Read and edit") })
                                TextButton(enabled = isValidPatp(ship), onClick = { onShare(c.id, ship, shareEdit); shareShip = "" }) { Text("Share") }
                            }
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth().clickable { editingId = c.id; editName = c.name; editColour = c.color }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(calendarHexColor(c.color) ?: MaterialTheme.colorScheme.primary))
                            Text(c.name.ifBlank { c.id }, modifier = Modifier.weight(1f))
                            val acc = shares?.accepted?.get(c.id)
                            Text(
                                when (c.kind) {
                                    "google" -> "Google"
                                    "caldav" -> "Followed"
                                    "ship" -> when {
                                        acc == null -> "Shared with you"
                                        acc.error.isNotBlank() -> "Shared with you · ${acc.error}"
                                        else -> "Shared with you by ${acc.host}" + if (acc.mode == "edit") "" else " · read only"
                                    }
                                    else -> if (shares?.shares?.get(c.id).isNullOrEmpty()) "" else "Shared with ${shares!!.shares[c.id]!!.size}"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (acc?.error?.isNotBlank() == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (!shares?.accepted.isNullOrEmpty()) {
                    TextButton(onClick = onSync) { Text("Pull shared calendars now") }
                }
                HorizontalDivider()
                Text(
                    if (zone == null) "Times are read as UTC: the calendar has no zone." else "Times are in $zone.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (deviceZone != null && deviceZone != zone) {
                    TextButton(onClick = { onSetZone(deviceZone); onDismiss() }) { Text("Use this device's zone ($deviceZone)") }
                }
                HorizontalDivider()
                Text("New calendar", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(value = newColour, onValueChange = { newColour = it }, label = { Text("Colour, #rrggbb") }, singleLine = true)
                TextButton(enabled = newName.isNotBlank(), onClick = { onAdd(newName.trim(), newColour.trim()); newName = "" }) { Text("Add") }
                if (onOpenWebSettings != null) {
                    HorizontalDivider()
                    Text(
                        "Sharing over CalDAV, following another calendar, Google and ICS feeds are set up on the calendar's own page.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { onOpenWebSettings(); onDismiss() }) { Text("Open the calendar's page") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/**
 * What is to do, soonest due first and undated last, the done ones
 * folded under; a line at the top adds one. Mirrors the calendar's
 * own Tasks view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TasksView(
    tasks: List<CalendarTask>?,
    today: LocalDate,
    calendars: List<CalendarInfo>,
    readOnly: Set<String>,
    colourOf: (CalendarTask) -> Color?,
    status: String?,
    onTick: (id: String, done: Boolean) -> Unit,
    onOpen: (CalendarTask) -> Unit,
    onAdd: (name: String, due: LocalDate?, cal: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf("") }
    var due by remember { mutableStateOf<LocalDate?>(null) }
    var cal by remember(calendars) { mutableStateOf(calendars.firstOrNull()?.id) }
    var picking by remember { mutableStateOf(false) }
    var showDone by remember { mutableStateOf(false) }
    fun add() {
        val n = name.trim()
        if (n.isEmpty()) return
        onAdd(n, due, cal)
        name = ""; due = null
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, placeholder = { Text("New task") }, singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { add() }),
            )
            TextButton(onClick = { picking = true }) { Text(due?.let { "${it.dayOfMonth} ${MonthNames.ENGLISH_ABBREVIATED.names[it.monthNumber - 1]}" } ?: "Due") }
            IconButton(onClick = ::add, enabled = name.isNotBlank()) { Icon(Icons.Filled.Add, contentDescription = "Add task") }
        }
        if (calendars.size > 1) {
            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(calendars, key = { it.id }) { c ->
                    FilterChip(selected = cal == c.id, onClick = { cal = c.id }, label = { Text(c.name.ifBlank { c.id }) })
                }
            }
        }
        status?.let {
            Text(
                it, style = MaterialTheme.typography.bodySmall,
                color = if (it.endsWith("…")) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (tasks == null) {
            Text("Looking…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
            return@Column
        }
        val open = taskOrder(tasks.filter { !it.done })
        val done = taskOrder(tasks.filter { it.done })
        LazyColumn(Modifier.fillMaxSize()) {
            if (open.isEmpty()) item { Text("Nothing to do.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
            items(open, key = { it.id }) { t -> TaskLine(t, today, colourOf(t), t.cal !in readOnly, onTick, onOpen) }
            if (done.isNotEmpty()) {
                item {
                    TextButton(onClick = { showDone = !showDone }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(if (showDone) "Hide done (${done.size})" else "Done (${done.size})")
                    }
                }
                if (showDone) items(done, key = { it.id }) { t -> TaskLine(t, today, colourOf(t), t.cal !in readOnly, onTick, onOpen) }
            }
        }
    }
    if (picking) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (due ?: today).atTime(0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    due = state.selectedDateMillis?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { due = null; picking = false }) { Text("No date") } },
        ) { DatePicker(state = state) }
    }
}

@Composable
private fun TaskLine(t: CalendarTask, today: LocalDate, colour: Color?, editable: Boolean, onTick: (String, Boolean) -> Unit, onOpen: (CalendarTask) -> Unit) {
    val dueDay = t.dueDate()
    val late = !t.done && dueDay != null && dueDay < today
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(t) }.padding(start = 8.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Checkbox(checked = t.done, onCheckedChange = { onTick(t.id, it) }, enabled = editable)
        Box(Modifier.size(10.dp).clip(CircleShape).background(colour ?: MaterialTheme.colorScheme.primary))
        Column(Modifier.weight(1f)) {
            Text(
                t.name.ifBlank { "(untitled)" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textDecoration = if (t.done) TextDecoration.LineThrough else null,
            )
            val line = listOf(t.note, t.tags.joinToString(" ") { "#$it" }).filter { it.isNotBlank() }.joinToString(" · ")
            if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (dueDay != null) {
            Text(
                "${dueDay.dayOfMonth} ${MonthNames.ENGLISH_ABBREVIATED.names[dueDay.monthNumber - 1]}" + if (late) " · overdue" else "",
                style = MaterialTheme.typography.labelSmall,
                color = if (late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider(Modifier.padding(start = 48.dp))
}
