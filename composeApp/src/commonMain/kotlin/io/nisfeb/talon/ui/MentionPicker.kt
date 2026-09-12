package io.nisfeb.talon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    /** Row the keyboard has highlighted; Tab completes this one.
     *  Coerced into range so a shrinking list can't point past the end.
     *  Mirrors [EmojiPickerDropdown]. */
    selectedIndex: Int = 0,
) {
    if (suggestions.isEmpty()) return
    val sel = selectedIndex.coerceIn(0, suggestions.lastIndex)
    val listState = rememberLazyListState()
    // Keep the highlighted row visible as the user arrows past the fold.
    LaunchedEffect(sel) { listState.animateScrollToItem(sel) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        shadowElevation = 4.dp,
    ) {
        LazyColumn(state = listState, modifier = Modifier.heightIn(max = 240.dp)) {
            itemsIndexed(items = suggestions, key = { _, s -> s.ship }) { idx, s ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (idx == sel) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                Color.Transparent
                            },
                        )
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
                            // The word name leads, and for a comet it is
                            // the only thing shown: its @p is fifty-six
                            // characters of exactly what the name exists
                            // to replace. Two comets can abridge the
                            // same, so the full nym follows when one is
                            // ambiguous -- the longer name, not the @p,
                            // is what tells them apart.
                            s.label,
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
    /**
     * The line under the nickname: what this ship is called.
     *
     * A comet's word name, never its @p -- fifty-six characters of
     * exactly what the name exists to replace. Where two rows would
     * read the same, the unabridged nym instead, since that is what
     * tells them apart. A ship with no word name shows its @p, which
     * is the only name it has.
     */
    val label: String,
    /** The abridged word name, or null for a ship that has none.
     *  Matching runs against the full nym too; see [nymMatches]. */
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
 * against the nickname, the raw patp, and a comet's word name, so
 * `@sam`, `@doznec`, `@admire` and `@..admire...attune` all find the
 * same ship. Capped at 6 entries.
 */
fun suggestionsFor(
    query: String,
    contactMap: ContactMap,
    allShips: Collection<String>,
): List<Suggestion> {
    fun nymOf(ship: String) = Mnemonym.forShip(ship)

    val q = query.lowercase()
    if (q.isEmpty()) return labelled(allShips.take(6), contactMap)
    val qNym = q.trimStart('.')
    val matches = mutableListOf<String>()
    for (ship in allShips) {
        if (matches.size >= 6) break
        val shipLower = ship.lowercase().removePrefix("~")
        val nick = contactMap.nickname(ship)
        if (shipLower.startsWith(q) ||
            nick?.lowercase()?.contains(q) == true ||
            nymMatches(qNym, ship)
        ) {
            matches += ship
        }
    }
    return labelled(matches, contactMap)
}

/**
 * Name each row, and lengthen only the names that need it.
 *
 * A comet is shown by its word name and never by its @p. Two comets
 * can abridge to the same two words, though, and a list offering the
 * same name twice is no use to anybody -- so when that happens the
 * unabridged nym is shown instead, for those rows only. That is the
 * longer name, not the @p: the @p disambiguates but tells you nothing,
 * and the whole point of the name is that people can read it.
 */
private fun labelled(ships: List<String>, contactMap: ContactMap): List<Suggestion> {
    val short = ships.associateWith { wordName(it) }
    val clashing = short.values.filterNotNull()
        .groupingBy { it }.eachCount()
        .filterValues { it > 1 }.keys
    return ships.map { ship ->
        val name = short[ship]
        Suggestion(
            ship = ship,
            nickname = contactMap.nickname(ship),
            label = when {
                name == null -> ship
                name in clashing -> fullWordName(ship) ?: name
                else -> name
            },
            mnemonym = name,
        )
    }
}

/** A ship's word name: a comet's own, or one looked up for a planet
 *  when the reader asked for those. */
private fun wordName(ship: String): String? =
    Mnemonym.display(ship)
        ?: if (AzimuthNames.enabled.value) AzimuthNames.nameFor(ship) else null

private fun fullWordName(ship: String): String? =
    Mnemonym.forShip(ship) ?: AzimuthNames.fullNameFor(ship)

/**
 * Whether [qNym] (dots already stripped from the front) picks out this
 * ship by its word name.
 *
 * Three ways in, because there are two different strings a person
 * might be going from. What they see anywhere else in the app is the
 * abridged `..first...last`, so typing that, or just the last word of
 * it, has to work -- it used to not, which made the name on screen the
 * one string that found nothing. What they may have been given or
 * pasted is the full nym, so a prefix of that has to work too, and any
 * single word of it, since the middle is where two comets differ.
 *
 * A loose net is the right shape here: every row carries its exact @p,
 * and the picker is for narrowing down to the ship you then verify,
 * not for deciding on your behalf.
 */
private fun nymMatches(qNym: String, ship: String): Boolean {
    if (qNym.isEmpty()) return false
    val full = Mnemonym.forShip(ship)?.trimStart('.') ?: return false
    if (full.startsWith(qNym)) return true
    val abridged = Mnemonym.display(ship)?.trimStart('.')
    if (abridged != null && abridged.startsWith(qNym)) return true
    return full.split('.').any { it.startsWith(qNym) }
}
