// Adapted: TalonApplication coupling replaced with `db: AppDatabase`,
// `activeShip: String?`, and `onGenerateNow: () -> Unit` injected
// parameters. The Android-only DailyDigest service (alarm scheduler +
// generator) stays in app/; here we read the latest stored digest
// straight from the daily_digests table. On desktop the alarm never
// fires so this screen falls into the empty-state branch and the
// `onGenerateNow` callback is wired to a no-op.
// Keep in sync with production until app/ is removed in Stage F.
package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.Bucket
import io.nisfeb.talon.data.DailyDigestEntity
import io.nisfeb.talon.data.DigestItem
import io.nisfeb.talon.data.WeatherToday
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

/**
 * Today's brief screen. Renders weather + AI summary + three priority
 * buckets (Mentions / Watchword hits / Unread). See spec §UI.
 *
 * Today is determined by `LocalDate.now(zone)` — if the latest digest
 * is from a previous day (e.g., user opened the app at 5:50am before
 * today's 6am alarm), the screen falls into the empty-state branch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyDigestScreen(
    db: AppDatabase,
    activeShip: String?,
    onBack: () -> Unit,
    onOpenMessage: (whom: String, postId: String) -> Unit,
    /** Manual "Generate now" tap. On Android this routes into
     *  `DailyDigest.generateAndNotifyAsync`; on desktop the Stage F
     *  port hasn't wired a desktop digest generator yet, so the host
     *  passes a no-op. */
    onGenerateNow: () -> Unit = {},
) {
    // remember the upstream Flow so recompositions don't re-subscribe
    // (which would re-run the flatMapLatest pipeline every frame).
    val digestFlow: Flow<DailyDigestEntity?> = remember(db, activeShip) {
        if (activeShip.isNullOrBlank()) flowOf(null)
        else db.dailyDigests().streamLatestForShip(activeShip)
    }
    val digest by digestFlow.collectAsState(initial = null)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Today's brief") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            val today = digest?.takeIf { isTodayLocal(it.dateLocal) }
            if (today == null) {
                EmptyDigest(onGenerateNow = onGenerateNow)
            } else {
                Body(today, onOpenMessage)
            }
        }
    }
}

@Composable
private fun Body(
    digest: DailyDigestEntity,
    onOpenMessage: (String, String) -> Unit,
) {
    val items = remember(digest.itemsJson) {
        runCatching { JSON.decodeFromString<List<DigestItem>>(digest.itemsJson) }
            .getOrDefault(emptyList())
    }
    val weather = remember(digest.weatherJson) {
        digest.weatherJson?.let {
            runCatching { JSON.decodeFromString<WeatherToday>(it) }.getOrNull()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        weather?.let { item { WeatherCard(it) } }
        digest.summaryText?.let { item { SummaryCard(it) } }
        renderBucket("Mentions", Bucket.MENTION, items, onOpenMessage)
        renderBucket("Watchword hits", Bucket.WATCHWORD, items, onOpenMessage)
        renderBucket("Unread", Bucket.UNREAD, items, onOpenMessage)
    }
}

private fun LazyListScope.renderBucket(
    label: String,
    bucket: Bucket,
    items: List<DigestItem>,
    onOpenMessage: (String, String) -> Unit,
) {
    val rows = items.filter { it.bucket == bucket }
    if (rows.isEmpty()) return
    item {
        Text(
            "$label (${rows.size})",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    items(rows, key = { "${it.whom}|${it.postId}" }) { row ->
        ItemRow(row, onOpenMessage)
    }
}

@Composable
private fun WeatherCard(w: WeatherToday) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(w.emoji, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(12.dp))
            Text(
                "${w.conditionLabel} · ${w.highF.toInt()}° / ${w.lowF.toInt()}°",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun SummaryCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemRow(item: DigestItem, onOpen: (String, String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { onOpen(item.whom, item.postId) },
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.authorPatp.ifBlank { "(unknown)" },
                    style = MaterialTheme.typography.labelLarge,
                )
                if (item.matchedTerm != null) {
                    Spacer(Modifier.width(8.dp))
                    AssistChip(
                        onClick = {},
                        label = { Text(item.matchedTerm) },
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                item.snippet,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun EmptyDigest(onGenerateNow: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Nothing to brief today.", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onGenerateNow) { Text("Generate now") }
    }
}

private val JSON = Json { ignoreUnknownKeys = true }

private fun isTodayLocal(dateLocal: String): Boolean =
    dateLocal == java.time.LocalDate.now(java.time.ZoneId.systemDefault()).toString()
