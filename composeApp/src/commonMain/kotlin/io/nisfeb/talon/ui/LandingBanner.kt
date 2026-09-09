package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Where a fresh local ship is in getting ready, after it has booted
 * and the app has signed in but before there is anything to look at.
 * The ship is installing desks and answering slowly, the group join
 * is being retried, and without this the app is an empty list with
 * no sign that anything is happening.
 */
data class LandingProgress(
    /** What we are waiting on, in plain words. */
    val step: String,
    /** Seconds since the landing began. */
    val elapsedSecs: Long,
    /** True once we have given up; the banner then offers Dismiss. */
    val failed: Boolean = false,
)

@Composable
fun LandingBanner(
    progress: LandingProgress,
    /** The ship's most recent terminal line, so the wait is visibly
     *  the ship working rather than the app hanging. */
    terminalLine: String?,
    onOpenTerminal: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (progress.failed) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (!progress.failed) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (progress.failed) "Your ship is up, but the group did not arrive"
                        else "Getting your ship ready",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Text(
                        progress.step + "  ·  " + formatElapsed(progress.elapsedSecs),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!progress.failed) {
                        Text(
                            "The ship is installing its updates and using most of a CPU core. " +
                                "Talon may stutter or freeze for a minute at a time until it settles; it recovers on its own.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    terminalLine?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onOpenTerminal) { Text("Terminal") }
                if (progress.failed) TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

private fun formatElapsed(secs: Long): String =
    if (secs < 60) "${secs}s" else "${secs / 60}m ${secs % 60}s"
