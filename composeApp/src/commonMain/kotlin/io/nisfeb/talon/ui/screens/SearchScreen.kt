// TEMPORARY DUPLICATE of app/src/main/java/io/nisfeb/talon/ui/screens/SearchScreen.kt
// Diverges from production: callers of db.messages().search and
// db.contacts().search now wrap the needle in escapeLikeNeedle()
// (round 6 fix) — production has a latent wildcard-injection bug
// for queries containing % or _; this port fixes it. Keep otherwise
// in sync until app/ is removed in Stage F.
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
    onOpenMessage: (whom: String, postId: String, parentId: String?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // composeApp's SearchScreen is the substring + people-search subset.
    // Production app/ also has semantic-search and important-messages
    // highlights backed by ML Kit's text embedder + an EmbeddingIndexer
    // — both Android-only. They're gated off here so users see the
    // search-text-and-people experience without the AI affordances.
    var query by remember { mutableStateOf("") }
    val trimmed = query.trim()

    val substringResults by remember(trimmed) {
        if (trimmed.length < 2) flowOf(emptyList())
        else db.messages().search(io.nisfeb.talon.data.escapeLikeNeedle(trimmed))
    }.collectAsState(initial = emptyList<MessageEntity>())

    val results = substringResults

    val people by remember(trimmed) {
        if (trimmed.length < 2) flowOf(emptyList())
        else db.contacts().search(io.nisfeb.talon.data.escapeLikeNeedle(trimmed))
    }.collectAsState(initial = emptyList<ContactEntity>())

    val contactMap by remember {
        contactMapFlow(
            db.contacts().stream(),
            db.clubs().stream(),
            db.groups().streamGroups(),
            db.groups().streamChannelGroups(),
        )
    }.collectAsState(initial = ContactMap.EMPTY)

    val semanticEnabled = false  // gated off in composeApp; see KDoc above
    val smartMode = false
    val highlightsEnabled = false
    val highlights = remember { mutableStateOf<List<MessageEntity>>(emptyList()) }
    val semanticBusy = false
    data class IndexProgressStub(val indexed: Int = 0, val total: Int = 0)
    val indexProgress = IndexProgressStub()

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
        // Smart-search toggle + indexer status are gated off in
        // composeApp (Embedder + EmbeddingIndexer are Android-only).
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
                    ResultRow(m, contactMap) {
                        onOpenMessage(m.whom, m.id, m.parentId)
                    }
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
                        ResultRow(m, contactMap) {
                            onOpenMessage(m.whom, m.id, m.parentId)
                        }
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
