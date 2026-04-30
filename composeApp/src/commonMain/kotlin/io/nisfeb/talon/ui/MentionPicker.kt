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
 * Dropdown of contact suggestions for @mention autocomplete. Shown
 * above the composer while the caret sits inside a `@query` token.
 */
@Composable
fun MentionPicker(
    suggestions: List<Suggestion>,
    onPick: (ship: String) -> Unit,
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
            items(items = suggestions, key = { it.ship }) { s ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(s.ship) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        if (s.nickname != null) {
                            Text(
                                s.nickname,
                                style = MaterialTheme.typography.bodyMedium
                                    .copy(fontWeight = FontWeight.SemiBold),
                            )
                        }
                        Text(
                            s.ship,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

data class Suggestion(val ship: String, val nickname: String?)

/**
 * Inspect the composer text + caret position for an active mention
 * trigger — either `@query` or `~query`. Returns the query portion (no
 * trigger char) and the start index of the trigger if the caret is
 * inside such a token; null otherwise.
 *
 * Rules: the trigger must be word-initial (preceded by start-of-text or
 * whitespace) and the query chars so far must be patp-shaped
 * (lowercase letters + dashes).
 */
fun detectMentionQuery(text: String, cursor: Int): Pair<String, Int>? {
    if (cursor == 0 || cursor > text.length) return null
    var i = cursor - 1
    while (i >= 0) {
        val c = text[i]
        if (c == '@' || c == '~') break
        if (c == ' ' || c == '\n' || c == '\t') return null
        if (!(c.isLetter() || c == '-')) return null
        i--
    }
    if (i < 0) return null
    val before = if (i == 0) ' ' else text[i - 1]
    if (!(before == ' ' || before == '\n' || before == '\t')) return null
    val query = text.substring(i + 1, cursor)
    return query to i
}

/**
 * Shortlist contacts matching a query (case-insensitive). Matches
 * against both the nickname and the raw patp. Capped at 6 entries.
 */
fun suggestionsFor(
    query: String,
    contactMap: ContactMap,
    allShips: Collection<String>,
): List<Suggestion> {
    val q = query.lowercase()
    if (q.isEmpty()) {
        return allShips.asSequence()
            .take(6)
            .map { Suggestion(it, contactMap.nickname(it)) }
            .toList()
    }
    val matches = mutableListOf<Suggestion>()
    for (ship in allShips) {
        if (matches.size >= 6) break
        val shipLower = ship.lowercase().removePrefix("~")
        val nick = contactMap.nickname(ship)
        if (shipLower.startsWith(q) || nick?.lowercase()?.contains(q) == true) {
            matches += Suggestion(ship, nick)
        }
    }
    return matches
}
