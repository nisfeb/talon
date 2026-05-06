package io.nisfeb.talon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.data.ReactionEntity
import io.nisfeb.talon.ui.ReactionPalette

/**
 * Per-reactor breakdown for a message's reactions. Long-press / right
 * click on any reaction chip surfaces this sheet — answers
 * "who reacted with what" without the full message-action sheet.
 *
 * Used by both DmChatScreen (top-level chat reactions) and
 * ThreadScreen (reactions inside a thread). [onOpenProfile] is
 * optional; ThreadScreen passes null because tapping a reactor
 * there should just close the sheet (no profile-sheet plumbing
 * inside threads yet).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReactionDetailsSheet(
    reactions: List<ReactionEntity>,
    contactMap: ContactMap,
    onDismiss: () -> Unit,
    onOpenProfile: ((String) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState()
    val sorted = remember(reactions) {
        reactions.sortedBy { contactMap.displayName(it.author).lowercase() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Reactions",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(bottom = 4.dp),
            )
            HorizontalDivider()
            sorted.forEach { r ->
                val rowModifier = if (onOpenProfile != null) {
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenProfile(r.author) }
                        .padding(vertical = 8.dp)
                } else {
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                }
                Row(
                    modifier = rowModifier,
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val label = contactMap.displayName(r.author)
                    Avatar(
                        label = label,
                        url = contactMap.avatar(r.author),
                        colorHex = contactMap.shipColor(r.author),
                        size = 32.dp,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                        )
                        if (label != r.author) {
                            Text(
                                r.author,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                    Text(
                        ReactionPalette.display(r.emoji),
                        fontFamily = EmojiFontFamily,
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
        }
    }
}
