package io.nisfeb.talon.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * A time of day as a button, "10:00 PM", that opens the clock to change it:
 * quiet hours' ends, a check-in's time. [minute] is minutes after midnight.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClockTimeButton(minute: Int, onPick: (Int) -> Unit, label: String? = null) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(listOfNotNull(label, minuteLabel(minute)).joinToString(" ")) }
    if (open) {
        val state = rememberTimePickerState(initialHour = minute / 60, initialMinute = minute % 60, is24Hour = false)
        AlertDialog(
            onDismissRequest = { open = false },
            text = { TimePicker(state = state) },
            confirmButton = { TextButton(onClick = { open = false; onPick(state.hour * 60 + state.minute) }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}
