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
                            // Keep the exact @p visible next to the friendly
                            // name — the row is how users verify WHICH ship
                            // they're about to mention.
                            s.mnemonym?.let { "${s.ship} · $it" } ?: s.ship,
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

data class Suggestion(
    val ship: String,
    val nickname: String?,
    /** Mnemonym shown alongside the patp when the naming setting is
     *  on; null otherwise (or for galaxies/stars). */
    val mnemonym: String? = null,
)

/**
 * Inspect the composer text + caret position for an active mention
 * trigger — either `@query` or `~query`. Returns the query portion (no
 * trigger char) and the start index of the trigger if the caret is
 * inside such a token; null otherwise.
 *
 * Rules: the trigger must be word-initial (preceded by start-of-text or
 * whitespace) and the query chars so far must be patp-shaped
 * (lowercase letters + dashes) or mnemonym-shaped (words joined by
 * dots — see [Mnemonym]).
 */
fun detectMentionQuery(text: String, cursor: Int): Pair<String, Int>? {
    if (cursor == 0 || cursor > text.length) return null
    var i = cursor - 1
    while (i >= 0) {
        val c = text[i]
        if (c == '@' || c == '~') break
        if (c == ' ' || c == '\n' || c == '\t') return null
        if (!(c.isLetter() || c == '-' || c == '.')) return null
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
 * against the nickname, the raw patp, and — when mnemonym naming is
 * on — the ship's mnemonym, so `@sam`, `@sampel` and `@accept.eng`
 * all find the same ship. Capped at 6 entries.
 */
fun suggestionsFor(
    query: String,
    contactMap: ContactMap,
    allShips: Collection<String>,
): List<Suggestion> {
    val mnemonyms = contactMap.mnemonymNames
    fun nymOf(ship: String) = if (mnemonyms) Mnemonym.forShip(ship) else null

    val q = query.lowercase()
    if (q.isEmpty()) {
        return allShips.asSequence()
            .take(6)
            .map { Suggestion(it, contactMap.nickname(it), nymOf(it)) }
            .toList()
    }
    // ponytail: whole-nym prefix match (leading dots stripped), so
    // "@accept", "@.accept" and "@accept.eng" all hit — a mid-nym word
    // like "@engulf" doesn't. Widen to per-word prefixes if it bites.
    val qNym = q.trimStart('.')
    val matches = mutableListOf<Suggestion>()
    for (ship in allShips) {
        if (matches.size >= 6) break
        val shipLower = ship.lowercase().removePrefix("~")
        val nick = contactMap.nickname(ship)
        val nym = nymOf(ship)
        if (shipLower.startsWith(q) ||
            nick?.lowercase()?.contains(q) == true ||
            (qNym.isNotEmpty() && nym?.trimStart('.')?.startsWith(qNym) == true)
        ) {
            matches += Suggestion(ship, nick, nym)
        }
    }
    return matches
}
