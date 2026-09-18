package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.orrery.OrreryAction
import kotlinx.datetime.Instant

/**
 * What orrery has proposed, waiting for a yes or a no.
 *
 * The analyst reads what it is allowed to read and proposes; nothing
 * it proposes happens until somebody says so. This is where that is
 * said. A proposal is a question, and the ones already approved are
 * here too, because an approved action is still something to carry
 * out or to drop.
 */
@Composable
fun OrreryActionsScreen(
    actions: List<OrreryAction>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Read what is waiting now, rather than whatever the last pass saw. */
    onShown: suspend () -> Unit = {},
    onOpen: (OrreryAction) -> Unit,
) {
    androidx.compose.runtime.LaunchedEffect(Unit) { onShown() }
    Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            io.nisfeb.talon.ui.NavIcon(onBack = onBack)
            Text(
                "Actions",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 4.dp).weight(1f),
            )
        }
        HorizontalDivider()
        Body(actions, onOpen)
    }
}

@Composable
private fun Body(actions: List<OrreryAction>, onOpen: (OrreryAction) -> Unit) {
    if (actions.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Nothing to answer", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Orrery proposes a thing to do when it reads one. Nothing it proposes happens until you say so.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    // Waiting on an answer first: that is what this screen is for.
    val ordered = actions.sortedBy { if (it.status == "proposed") 0 else 1 }
    LazyColumn(Modifier.fillMaxSize()) {
        items(ordered, key = { it.id }) { a ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(a) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        a.title.ifBlank { a.kind },
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = if (a.status == "proposed") FontWeight.SemiBold else FontWeight.Normal,
                        ),
                    )
                    val due = a.due?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    Text(
                        listOfNotNull(
                            a.kind,
                            if (a.status == "proposed") "waiting for you" else a.status,
                            a.by.takeIf { it.isNotBlank() },
                            due?.let { "due ${it.toString().take(10)}" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}
