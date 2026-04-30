// No divergence — pure Compose. Keep in sync until app/ is removed in Stage F.
package io.nisfeb.talon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Dropdown of slash-command suggestions for the composer. Appears
 * above the text field while the user is typing `/…` before the first
 * whitespace.
 */
@Composable
fun SlashPicker(
    suggestions: List<SlashCommandSpec>,
    onPick: (SlashCommandSpec) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (suggestions.isEmpty()) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        shadowElevation = 4.dp,
    ) {
        LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
            items(items = suggestions, key = { it.name }) { cmd ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(cmd) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            cmd.synopsis,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                        Text(
                            cmd.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
