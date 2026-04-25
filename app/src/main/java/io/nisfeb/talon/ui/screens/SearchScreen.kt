package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.ContactEntity
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.ui.Avatar
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.contactMapFlow
import io.nisfeb.talon.urbit.StoryCache
import kotlinx.coroutines.flow.flowOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SearchScreen(
    db: AppDatabase,
    onOpenConversation: (whom: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as io.nisfeb.talon.TalonApplication
    val aiState by app.aiSettings.state.collectAsState()
    val semanticEnabled = aiState.semanticSearchEnabled
    var query by remember { mutableStateOf("") }
    var smartMode by remember(semanticEnabled) { mutableStateOf(false) }
    val trimmed = query.trim()

    val substringResults by remember(trimmed, smartMode) {
        if (smartMode || trimmed.length < 2) flowOf(emptyList())
        else db.messages().search(trimmed)
    }.collectAsState(initial = emptyList<MessageEntity>())

    val semanticResults = remember { mutableStateOf<List<MessageEntity>>(emptyList()) }
    var semanticBusy by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(trimmed, smartMode) {
        if (!smartMode || trimmed.length < 2) {
            semanticResults.value = emptyList()
            return@LaunchedEffect
        }
        semanticBusy = true
        semanticResults.value = runCatching {
            val vec = app.embedder.embed(trimmed) ?: return@runCatching emptyList()
            val hits = io.nisfeb.talon.ai.semanticSearch(vec, db.embeddings())
            // Hits are (whom, id, score); fetch the underlying messages
            // and preserve hit-order (cosine-sim descending).
            hits.mapNotNull { db.messages().getOne(it.whom, it.id) }
        }.getOrElse { emptyList() }
        semanticBusy = false
    }

    val results = if (smartMode) semanticResults.value else substringResults

    val people by remember(trimmed) {
        if (trimmed.length < 2) flowOf(emptyList())
        else db.contacts().search(trimmed)
    }.collectAsState(initial = emptyList<ContactEntity>())

    val contactMap by remember {
        contactMapFlow(
            db.contacts().stream(),
            db.clubs().stream(),
            db.groups().streamGroups(),
            db.groups().streamChannelGroups(),
        )
    }.collectAsState(initial = ContactMap.EMPTY)

    val indexProgress by app.embeddingIndexer.progress.collectAsState()

    // Important-messages highlights: scored against the centroid of
    // the user's bookmarks. Recomputed when bookmarks change or when
    // the indexer adds new rows. Hidden unless the user enabled the
    // feature in Settings.
    val highlightsEnabled = aiState.importantMessagesEnabled
    val highlights = remember { mutableStateOf<List<MessageEntity>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(highlightsEnabled, indexProgress.indexed) {
        highlights.value = if (!highlightsEnabled) emptyList()
        else io.nisfeb.talon.ai.computeHighlights(db)
    }

    // Catch the index up to whatever new messages have landed since
    // last indexer pass. start() is a no-op if already running, and
    // skips already-embedded rows on the cheap. Cheaper than
    // embedding every SSE message inline (which would compete with
    // the messaging hot path on a reconnect flurry). Gated on the
    // user enabling smart search.
    androidx.compose.runtime.LaunchedEffect(semanticEnabled) {
        if (semanticEnabled) app.embeddingIndexer.start()
    }

    Column(modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = {
                    Text(if (smartMode) "Search by meaning" else "Search messages")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (semanticEnabled) {
                FilterChip(
                    selected = smartMode,
                    onClick = { smartMode = !smartMode },
                    label = { Text(if (smartMode) "✨ Smart on" else "✨ Smart") },
                )
            }
            if (semanticEnabled && smartMode) {
                val p = indexProgress
                val status = when {
                    semanticBusy -> "embedding query…"
                    p.running && p.total > 0 -> "indexing ${p.indexed}/${p.total}…"
                    p.running -> "scanning local archive…"
                    p.total == 0 -> "no messages indexed yet"
                    else -> "${p.indexed} messages indexed"
                }
                Text(
                    status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
        if (trimmed.length < 2 && highlightsEnabled && highlights.value.isNotEmpty()) {
            // No query yet, but the user has highlights — show them
            // as the empty state. Same row component as a search hit.
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                item(key = "__highlights_header", contentType = "header") {
                    SectionHeader("Highlights")
                }
                items(
                    items = highlights.value,
                    key = { "hl:${it.whom}:${it.id}" },
                    contentType = { "hl" },
                ) { m ->
                    ResultRow(m, contactMap) { onOpenConversation(m.whom) }
                    HorizontalDivider()
                }
            }
        } else if (trimmed.length < 2) {
            Text(
                "Type at least two characters.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else if (results.isEmpty() && people.isEmpty()) {
            Text(
                "No matches.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                if (people.isNotEmpty()) {
                    item(key = "__people_header", contentType = "header") {
                        SectionHeader("People")
                    }
                    items(
                        items = people,
                        key = { "person:${it.ship}" },
                        contentType = { "person" },
                    ) { p ->
                        PersonRow(p) { onOpenConversation(p.ship) }
                        HorizontalDivider()
                    }
                }
                if (results.isNotEmpty()) {
                    item(key = "__msgs_header", contentType = "header") {
                        SectionHeader("Messages")
                    }
                    items(
                        items = results,
                        key = { "${it.whom}:${it.id}" },
                        contentType = { "hit" },
                    ) { m ->
                        ResultRow(m, contactMap) { onOpenConversation(m.whom) }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(m: MessageEntity, contactMap: ContactMap, onClick: () -> Unit) {
    val preview = remember(m.id, m.contentJson) {
        StoryCache.textFor(m.id, m.contentJson).replace('\n', ' ')
    }
    val title = remember(m.whom, contactMap) { contactMap.conversationLabel(m.whom) }
    val authorLabel = remember(m.author, contactMap) { contactMap.displayName(m.author) }
    val stamp = remember(m.sentMs) { DATE_FORMAT.format(Date(m.sentMs)) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "$title · $authorLabel · $stamp",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(preview, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
    }
}

private val DATE_FORMAT = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())

@Composable
private fun SectionHeader(label: String) {
    Text(
        label.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun PersonRow(contact: ContactEntity, onClick: () -> Unit) {
    val label = contact.nickname?.takeIf { it.isNotBlank() } ?: contact.ship
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(
            label = label,
            url = contact.avatarUrl,
            colorHex = contact.color,
            size = 40.dp,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
            if (!contact.nickname.isNullOrBlank() && contact.nickname != contact.ship) {
                Text(
                    contact.ship,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
