package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.nisfeb.talon.urbit.shortNameProblem
import io.nisfeb.talon.urbit.shortNameTyped

/**
 * A new group's or channel's short name, the end of its id ([idPrefix]
 * shows the rest). Optional: left empty, one is picked at random.
 */
@Composable
fun ShortNameField(value: String, onValueChange: (String) -> Unit, idPrefix: String, enabled: Boolean) {
    val problem = shortNameProblem(value)
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(shortNameTyped(it)) },
        label = { Text("Short name (optional)") },
        singleLine = true,
        enabled = enabled,
        isError = problem != null,
        supportingText = {
            Text(problem ?: if (value.isEmpty()) "In its links. Fixed once made. Left empty, one is picked at random." else "$idPrefix$value")
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
