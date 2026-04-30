// No divergence — pure Compose. Keep in sync until app/ is removed in Stage F.
package io.nisfeb.talon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.dp

/**
 * Dropdown of emoji suggestions for inline `:name` completion. Shown
 * above the composer while the caret sits inside a `:query` token.
 */
@Composable
fun EmojiPickerDropdown(
    suggestions: List<EmojiCatalog.Entry>,
    onPick: (EmojiCatalog.Entry) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (suggestions.isEmpty()) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        shadowElevation = 4.dp,
    ) {
        LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
            items(items = suggestions, key = { it.shortcode }) { e ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(e) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(e.glyph, style = MaterialTheme.typography.titleMedium)
                    Text(
                        e.shortcode,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

/**
 * Inspect composer text + caret for an active `:query` trigger.
 * Returns the query (without the leading colon) and the start index of
 * the colon when the caret is inside a valid token; null otherwise.
 *
 * Rules: the `:` must be at the start of the text or directly after
 * whitespace; the query must be at least 1 char and contain only
 * `[a-z0-9_+-]` (case-insensitive).
 */
fun detectEmojiQuery(text: String, cursor: Int): Pair<String, Int>? {
    if (cursor <= 0 || cursor > text.length) return null
    var i = cursor - 1
    while (i >= 0) {
        val c = text[i]
        if (c == ':') break
        if (c == ' ' || c == '\n' || c == '\t') return null
        if (!(c.isLetterOrDigit() || c == '_' || c == '+' || c == '-')) return null
        i--
    }
    if (i < 0) return null
    val before = if (i == 0) ' ' else text[i - 1]
    if (!(before == ' ' || before == '\n' || before == '\t')) return null
    val query = text.substring(i + 1, cursor)
    if (query.isEmpty()) return null
    return query to i
}
