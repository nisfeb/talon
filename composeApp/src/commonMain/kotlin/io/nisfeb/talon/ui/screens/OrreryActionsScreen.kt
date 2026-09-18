package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.orrery.OrreryAction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.Refresh
import kotlinx.coroutines.launch
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
    /** Answer one on the spot: approved or dismissed. */
    onDecide: (OrreryAction, String) -> Unit = { _, _ -> },
    onOpen: (OrreryAction) -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var refreshing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    fun refresh() {
        if (refreshing) return
        refreshing = true
        scope.launch { runCatching { onShown() }; refreshing = false }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { refresh() }
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
            if (refreshing) {
                androidx.compose.material3.CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.padding(12.dp).size(20.dp),
                )
            } else {
                androidx.compose.material3.IconButton(onClick = ::refresh) {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.Filled.Refresh,
                        contentDescription = "Refresh",
                    )
                }
            }
        }
        HorizontalDivider()
        Body(actions, onOpen, onDecide)
    }
}

@Composable
private fun Body(
    actions: List<OrreryAction>,
    onOpen: (OrreryAction) -> Unit,
    onDecide: (OrreryAction, String) -> Unit,
) {
    val waiting = actions.filter { it.status == "proposed" }
    val settled = actions.filter { it.status != "proposed" }
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
    LazyColumn(Modifier.fillMaxSize()) {
        // What needs you, first and unmistakable: its own heading, a card
        // in the accent colour, and the answer right there on the row.
        item(key = "waiting-head") {
            Heading(
                if (waiting.isEmpty()) "Nothing waiting for you" else "Waiting for you · ${waiting.size}",
                accent = waiting.isNotEmpty(),
            )
        }
        items(waiting, key = { "w-" + it.id }) { a ->
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Column(Modifier.clickable { onOpen(a) }.padding(12.dp)) {
                    Text(
                        a.title.ifBlank { a.kind },
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        detail(a),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onDecide(a, "approved") }) { Text("Approve") }
                        OutlinedButton(onClick = { onDecide(a, "dismissed") }) { Text("Dismiss") }
                    }
                }
            }
        }
        // Already answered: kept, quieter, below a line of its own.
        if (settled.isNotEmpty()) {
            item(key = "settled-head") { Heading("Approved, still to be done", accent = false) }
            items(settled, key = { "s-" + it.id }) { a ->
                Column(
                    Modifier.fillMaxWidth().clickable { onOpen(a) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        a.title.ifBlank { a.kind },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        detail(a),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
}

@Composable
private fun Heading(text: String, accent: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
    )
}

/** Kind, who proposed it, and when it is due, in one quiet line. */
private fun detail(a: OrreryAction): String {
    val due = a.due?.let { runCatching { Instant.parse(it) }.getOrNull() }
    return listOfNotNull(
        a.kind,
        a.status.takeIf { it != "proposed" },
        a.by.takeIf { it.isNotBlank() }?.let { "from $it" },
        due?.let { "due ${it.toString().take(10)}" },
    ).joinToString(" · ")
}
