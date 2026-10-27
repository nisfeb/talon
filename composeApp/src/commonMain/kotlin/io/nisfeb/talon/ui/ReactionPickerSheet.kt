package io.nisfeb.talon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The browse surface behind the reaction quick-bar: one Frequently-used
 * row, then the whole catalog by category, with a search that replaces
 * the lot with its matches. The quick-bar in the message menu stays the
 * fast path; this is only reached from its magnifier.
 *
 * [emojiPickerSections] is the pure assembly, so a test can pin it
 * without a composition; [ReactionPickerSheet] is a thin renderer.
 */

/**
 * The sections to show. A non-blank [query] collapses everything to one
 * "Results" section (empty list when nothing matches, so the sheet can
 * say so). Blank shows "Frequently used" (from [frequent], the usage
 * table's shortcodes, de-duped by canonical glyph) ahead of every
 * category in [EmojiCatalog.categories].
 */
fun emojiPickerSections(
    frequent: List<String>,
    query: String,
): List<Pair<String, List<EmojiCatalog.Entry>>> {
    val q = query.trim()
    if (q.isNotEmpty()) {
        val results = EmojiCatalog.search(q, limit = 80)
        return if (results.isEmpty()) emptyList() else listOf("Results" to results)
    }
    val out = mutableListOf<Pair<String, List<EmojiCatalog.Entry>>>()
    val freq = frequentEntries(frequent)
    if (freq.isNotEmpty()) out.add("Frequently used" to freq)
    out.addAll(EmojiCatalog.categories)
    return out
}

/**
 * Usage shortcodes as pickable entries: the glyph comes from
 * [ReactionPalette.display] so even a code the catalog lacks still
 * renders, and the canonical glyph de-dupes so ":heart:" and "❤️" don't
 * both show.
 */
private fun frequentEntries(frequent: List<String>): List<EmojiCatalog.Entry> {
    val seen = mutableSetOf<String>()
    val out = mutableListOf<EmojiCatalog.Entry>()
    for (code in frequent) {
        if (seen.add(ReactionPalette.normalize(code))) {
            out.add(EmojiCatalog.Entry(code, ReactionPalette.display(code), emptyList()))
        }
    }
    return out
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReactionPickerSheet(
    frequent: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        ReactionPickerContent(frequent, onPick)
    }
}

/** The sheet's body, split out so a UI test can render it without the
 *  modal wrapper (whose animation and popup window a test can't drive). */
@Composable
fun ReactionPickerContent(
    frequent: List<String>,
    onPick: (String) -> Unit,
) {
        var query by remember { mutableStateOf("") }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search emoji") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        val sections = emojiPickerSections(frequent, query)
        if (sections.isEmpty()) {
            Text(
                "No emoji match \"${query.trim()}\".",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                textAlign = TextAlign.Center,
            )
        } else {
            // A lazy grid under the sheet's unbounded height must be
            // capped or it throws on an infinite max-height measure; it
            // scrolls inside the cap.
            LazyVerticalGrid(
                columns = GridCells.Adaptive(48.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).padding(horizontal = 8.dp),
            ) {
                sections.forEach { (label, entries) ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                        )
                    }
                    // No item keys: a shortcode can repeat across the
                    // Frequently-used row and its category, and duplicate
                    // keys crash a lazy grid.
                    items(entries) { entry ->
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onPick(entry.shortcode) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                entry.glyph,
                                fontFamily = EmojiFontFamily,
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                    }
                }
            }
        }
}
