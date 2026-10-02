package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.util.Problem

/** [problem] said in words, its error whole behind "Copy error details". */
@Composable
fun ProblemLine(problem: Problem) {
    val clipboard = LocalClipboardManager.current
    NoteLine(problem.line, calm = problem.calm, details = problem.details) { clipboard.setText(AnnotatedString(it)) }
}

/** A line saying what went wrong, with the error whole behind "Copy error details" where there is one. */
@Composable
internal fun NoteLine(text: String, calm: Boolean, details: String?, onCopy: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (calm) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f).padding(vertical = 4.dp),
        )
        if (details != null) {
            TextButton(onClick = { onCopy(details) }) {
                Text("Copy error details", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
