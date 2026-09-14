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
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.calendar.CalendarAvailability
import io.nisfeb.talon.calendar.CalendarInfo
import io.nisfeb.talon.calendar.CalendarRepo
import io.nisfeb.talon.calendar.CalendarRow
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
    val calendars by repo.calendars.collectAsState()
    val hidden by repo.hidden.collectAsState()
    val zoneId by repo.zone.collectAsState()
    val error by repo.error.collectAsState()
    val zone = zoneFor(zoneId)
    val scope = rememberCoroutineScope()

    val today = remember { Instant.fromEpochMilliseconds(nowMs()).toLocalDateTime(TimeZone.currentSystemDefault()).date }
    var year by remember { mutableStateOf(today.year) }
    var month by remember { mutableStateOf(today.monthNumber) }
    var selected by remember { mutableStateOf(today) }
    var editing by remember { mutableStateOf<Pair<String?, EventDraft>?>(null) }
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
    val visible = remember(rows, hidden, tagFilter) {
        rows.orEmpty().filter { it.cal !in hidden && (tagFilter == null || tagFilter in it.tags) }
    }
    val byDay = remember(visible, zoneId) {
        val m = HashMap<LocalDate, MutableList<CalendarRow>>()
        visible.forEach { r -> daysOf(r, zone).forEach { d -> m.getOrPut(d) { mutableListOf() }.add(r) } }
        m
    }
    val calColors = remember(calendars) { calendars.associate { it.id to it.color } }
    fun colourOf(r: CalendarRow) = calendarHexColor(r.color ?: calColors[r.cal])

    fun openNew() {
        editing = null to EventDraft(date = selected, cal = calendars.firstOrNull { it.id !in hidden }?.id)
        editingIdx = null
        editingStartMs = null
    }
    fun openExisting(r: CalendarRow) {
        scope.launch {
            val json = repo.eventDetail(r.id)
            val d = json?.let { draftFromEvent(it, selected) }
            if (d == null) { status = "That event could not be read for editing."; return@launch }
            editing = r.id to d
            editingIdx = r.idx
            editingStartMs = r.l
        }
    }

    Column(modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            io.nisfeb.talon.ui.NavIcon(onBack = onBack)
            Text(
                "${MonthNames.ENGLISH_FULL.names[month - 1]} $year",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { if (month == 1) { month = 12; year -= 1 } else month -= 1 }) {
                Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
            }
            TextButton(onClick = { year = today.year; month = today.monthNumber; selected = today }) { Text("Today") }
            IconButton(onClick = { if (month == 12) { month = 1; year += 1 } else month += 1 }) {
                Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "Next month")
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
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
        }
        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
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
                            Modifier.fillMaxWidth().clickable { openExisting(r) }.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(colourOf(r) ?: MaterialTheme.colorScheme.primary))
                            Column(Modifier.weight(1f)) {
                                Text(r.name.ifBlank { "(untitled)" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

    editing?.let { (id, draft) ->
        EventEditor(
            initial = draft,
            existing = id != null,
            recurringOccurrence = id != null && draft.repeats && editingIdx != null,
            calendars = calendars,
            zones = zones,
            twentyFourHour = twentyFourHour,
            onDismiss = { editing = null },
            onSave = { d, editScope ->
                scope.launch {
                    val idx = editingIdx
                    val occurrence = editingStartMs?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(zone) }
                    val ok = when {
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
                    if (ok) { editing = null; status = null } else status = "The ship did not take the change."
                }
            },
            onDelete = if (id == null) null else {
                {
                    scope.launch {
                        val ok = repo.poke(buildJsonObject { put("action", "del-event"); put("id", id) })
                        if (ok) { editing = null; status = null } else status = "The ship did not take the change."
                    }
                }
            },
            onSkip = if (id == null || editingIdx == null) null else {
                {
                    scope.launch {
                        val ok = repo.poke(buildJsonObject { put("action", "skip-event"); put("id", id); put("idx", editingIdx!!) })
                        if (ok) { editing = null; status = null } else status = "The ship did not take the change."
                    }
                }
            },
        )
    }
    if (managing) {
        CalendarsDialog(
            calendars = calendars,
            onDismiss = { managing = false },
            onMakeLocal = { id -> scope.launch { if (!repo.makeLocal(id)) status = "The ship would not make that calendar local." } },
            onOpenWebSettings = onOpenWebSettings,
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

/** When a row is, on the day being looked at. */
private fun spanLabel(r: CalendarRow, day: LocalDate, zone: TimeZone, twentyFourHour: Boolean): String {
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
        title = { Text(if (existing) "Edit event" else "New event") },
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (d.cat == EventCat.DATE) "Date (the year is ignored)" else "Date", style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { pickingDate = true }) { Text(d.date.toString()) }
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
                if (d.cat != EventCat.DATE && d.rawKind != null) {
                    Text(
                        "Repeats by an imported rule (${d.rawKind}), kept as it is. Everything else here can change.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (d.cat != EventCat.DATE && d.rawKind == null) {
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
                    ready.cat != EventCat.DATE && ready.rawKind == null && ready.repeat == Repeat.WEEKLY && ready.weekdays.isEmpty() -> "Pick the weekdays."
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
        val start = (if (forUntil) d.until else d.date) ?: d.date
        val state = rememberDatePickerState(initialSelectedDateMillis = start.atTime(0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds())
        DatePickerDialog(
            onDismissRequest = { pickingDate = false; pickingUntil = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val picked = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.UTC).date
                        d = if (forUntil) d.copy(until = picked, count = 0) else d.copy(date = picked)
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
    onDismiss: () -> Unit,
    onMakeLocal: (id: String) -> Unit,
    onOpenWebSettings: (() -> Unit)?,
    onAdd: (name: String, colour: String) -> Unit,
    onEdit: (id: String, name: String, colour: String) -> Unit,
    onDelete: (id: String) -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    var newColour by remember { mutableStateOf("#1e3a5f") }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editName by remember { mutableStateOf("") }
    var editColour by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Calendars") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                calendars.forEach { c ->
                    if (editingId == c.id) {
                        OutlinedTextField(value = editName, onValueChange = { editName = it }, label = { Text("Name") }, singleLine = true)
                        OutlinedTextField(value = editColour, onValueChange = { editColour = it }, label = { Text("Colour, #rrggbb") }, singleLine = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { onEdit(c.id, editName, editColour); editingId = null }) { Text("Save") }
                            TextButton(onClick = { editingId = null }) { Text("Cancel") }
                            if (c.id != "default") TextButton(onClick = { onDelete(c.id); editingId = null }) { Text("Delete") }
                        }
                        if (c.kind != "local") {
                            Text(
                                "Followed calendars sync both ways. Make local stops the sync and keeps everything in it; the source is left alone.",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = { onMakeLocal(c.id); editingId = null }) { Text("Make local") }
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth().clickable { editingId = c.id; editName = c.name; editColour = c.color }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(calendarHexColor(c.color) ?: MaterialTheme.colorScheme.primary))
                            Text(c.name.ifBlank { c.id }, modifier = Modifier.weight(1f))
                            Text(
                                when (c.kind) { "google" -> "Google"; "caldav" -> "Followed"; else -> "" },
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
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
