package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.notify.Enrollment
import kotlinx.coroutines.launch

/**
 * Notifications on a device that gets them only through the relay
 * ([isRelayNotificationSetupNeeded]). Asked right after signing in, with
 * the +code just typed, and at launch until the owner says yes or not
 * now; Settings opens it again. [code] null asks for the +code.
 */
@Composable
fun NotificationSetupDialog(
    code: String?,
    enroll: suspend (code: String) -> Enrollment,
    onDone: () -> Unit,
    onNotNow: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var why by remember { mutableStateOf<String?>(null) }
    var on by remember { mutableStateOf<Enrollment.On?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) { if (on != null) onDone() else onNotNow() } },
        title = { Text(if (on != null) "Notifications are on" else "Get notifications on this iPhone?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val done = on
                if (done != null) {
                    Text(
                        if (done.alerts) "Talon will tell you when something arrives, with the app closed."
                        else "Calls will ring, but messages won't alert until you allow notifications for Talon in iOS Settings.",
                    )
                } else {
                    Text(
                        "With the app closed, an iPhone hears nothing from your ship on its own. Talon's relay watches " +
                            "your ship and sends this iPhone a notification when something arrives. It signs in to your " +
                            "ship once with your +code, keeps an encrypted session, and forgets the code.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (code == null) {
                        OutlinedTextField(
                            value = typed,
                            onValueChange = { typed = it },
                            label = { Text("+code") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    why?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            if (on != null) {
                TextButton(onClick = onDone) { Text("OK") }
            } else {
                TextButton(
                    enabled = !busy && (code != null || typed.isNotBlank()),
                    onClick = {
                        busy = true
                        why = null
                        scope.launch {
                            when (val r = enroll(code ?: typed.trim())) {
                                is Enrollment.On -> on = r
                                is Enrollment.NoToken -> why = r.why
                                is Enrollment.Refused -> why = r.why
                            }
                            busy = false
                        }
                    },
                ) { Text(if (busy) "Turning on…" else "Turn on") }
            }
        },
        dismissButton = {
            if (on == null) TextButton(enabled = !busy, onClick = onNotNow) { Text("Not now") }
        },
    )
}
