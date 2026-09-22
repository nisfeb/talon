package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.calendar.EventCat
import io.nisfeb.talon.calendar.EventDraft
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState

/** Which of the two a message is being turned into. */
enum class FromMessage { Event, Task }

/**
 * The first line of what somebody said, as a title.
 *
 * A message is not a title, so this takes the first line, stops at the
 * first sentence that ends, and cuts what is left to something a list
 * can show. What it gets wrong the person fixes in the field, which is
 * why the field is there.
 */
fun titleFromMessage(text: String, max: Int = 72): String {
    val line = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    val sentence = line.indexOfFirst { it == '.' || it == '?' || it == '!' }
    val cut = if (sentence in 12 until max) line.take(sentence) else line
    if (cut.length <= max) return cut
    val room = cut.take(max)
    val space = room.lastIndexOf(' ')
    return (if (space > max / 2) room.take(space) else room).trimEnd() + "…"
}

/**
 * What the title left out, for the description.
 *
 * When the title is the whole first line, that is every line after it.
 * When the title had to be cut, it is the whole message, so nothing
 * that was said is lost to a title that only had room for part of it.
 */
fun descriptionFromMessage(text: String, title: String): String {
    val lines = text.lines()
    val first = lines.indexOfFirst { it.isNotBlank() }
    if (first < 0) return ""
    val whole = lines[first].trim()
    if (title != whole) return text.trim()
    return lines.drop(first + 1).joinToString("\n").trim()
}

/**
 * Make an event or a todo out of a message.
 *
 * Small on purpose: what it is called, and when. Everything else a
 * calendar entry can carry is a field in the calendar's own editor,
 * and this is a thing done in passing, in the middle of a
 * conversation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageToCalendarDialog(
    kind: FromMessage,
    initialTitle: String,
    initialNote: String,
    zone: TimeZone,
    nowMs: Long,
    twentyFourHour: Boolean,
    onDismiss: () -> Unit,
    onSave: (EventDraft) -> Unit,
) {
    val here = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone)
    var title by remember { mutableStateOf(initialTitle) }
    var note by remember { mutableStateOf(initialNote) }
    // The next hour, which is nearly always what somebody means when
    // they make an event out of what was just said.
    var date by remember { mutableStateOf(if (kind == FromMessage.Event && here.hour >= 23) here.date.plusDay() else here.date) }
    // And a time the owner can change: the label used to promise one
    // that no picker offered, so every event landed on the next hour.
    val startMinute = if (here.hour >= 23) 9 * 60 else (here.hour + 1) * 60
    val time = rememberTimePickerState(initialHour = startMinute / 60, initialMinute = startMinute % 60, is24Hour = twentyFourHour)
    var dated by remember { mutableStateOf(kind == FromMessage.Event) }
    var picking by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (kind == FromMessage.Event) "New event" else "New task") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Description") },
                    minLines = 2,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    TextButton(onClick = { picking = true }) {
                        Text(
                            when {
                                kind == FromMessage.Task && !dated -> "No due date"
                                kind == FromMessage.Task -> "Due ${date}"
                                else -> "$date"
                            },
                        )
                    }
                }
                if (kind == FromMessage.Event) {
                    TimeInput(state = time, modifier = Modifier.padding(top = 4.dp))
                    Text(
                        "An hour long, on the calendar new events go to. The calendar's own editor has the rest.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = {
                    onSave(
                        if (kind == FromMessage.Event) {
                            EventDraft(name = title.trim(), note = note.trim(), date = date, minuteOfDay = time.hour * 60 + time.minute, durMin = 60)
                        } else {
                            EventDraft(
                                name = title.trim(),
                                note = note.trim(),
                                cat = EventCat.TODO,
                                date = date,
                                due = date.takeIf { dated },
                            )
                        },
                    )
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (picking) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atTime(0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds(),
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        date = Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date
                        dated = true
                    }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = {
                if (kind == FromMessage.Task) {
                    TextButton(onClick = { dated = false; picking = false }) { Text("No date") }
                } else {
                    TextButton(onClick = { picking = false }) { Text("Cancel") }
                }
            },
        ) { DatePicker(state = state) }
    }
}

private fun LocalDate.plusDay(): LocalDate =
    kotlinx.datetime.LocalDate.fromEpochDays(toEpochDays() + 1)
