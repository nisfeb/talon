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
import androidx.compose.runtime.produceState
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
    aiSettings: io.nisfeb.talon.ai.AiSettingsRepository,
    onOpenConversation: (whom: String) -> Unit,
    onOpenMessage: (whom: String, postId: String, parentId: String?) -> Unit,
    onBack: () -> Unit,
    /** Optional ML-backed search affordances. Android wires the real
     *  Embedder + EmbeddingIndexer; desktop passes null and the screen
     *  renders substring + people-search only (no smart mode chip, no
     *  index status, no highlights — the previous gated-off shape). */
    embedder: io.nisfeb.talon.ai.SearchEmbedderClient? = null,
    modifier: Modifier = Modifier,
) {
    val aiState by aiSettings.state.collectAsState()
    var query by remember { mutableStateOf("") }
    val trimmed = query.trim()

    // Debounce typing — `db.messages().search(...)` is a full-table LIKE
    // scan with no index. On a 50K-row archive every keystroke would
    // jank the typing thread; settle for 250ms after the user pauses.
    val debouncedTrimmed by produceState(initialValue = "", trimmed) {
        if (trimmed.length < 2) {
            value = trimmed
            return@produceState
        }
        kotlinx.coroutines.delay(250)
        value = trimmed
    }

    // Escape once per debounced needle, not twice per keystroke.
    val escapedNeedle = remember(debouncedTrimmed) {
        if (debouncedTrimmed.length < 2) ""
        else io.nisfeb.talon.data.escapeLikeNeedle(debouncedTrimmed)
    }

    val substringResults by remember(debouncedTrimmed, escapedNeedle) {
        if (debouncedTrimmed.length < 2) flowOf(emptyList())
        else db.messages().search(escapedNeedle)
    }.collectAsState(initial = emptyList<MessageEntity>())

    val people by remember(debouncedTrimmed, escapedNeedle) {
        if (debouncedTrimmed.length < 2) flowOf(emptyList())
        else db.contacts().search(escapedNeedle)
    }.collectAsState(initial = emptyList<ContactEntity>())

    // Set of whoms the user has any activity with — used to flag a
    // search result as "you've never DMed this person, tap to start"
    // rather than letting them silently land on a 404'd empty chat.
    // unreads has a row per chat the user has *received* activity for,
    // so it's a cheap proxy for "this conversation exists on the ship".
    val knownWhoms by remember {
        db.unreads().stream()
    }.collectAsState(initial = emptyList())
    val activeWhomSet = remember(knownWhoms) {
        knownWhoms.mapTo(hashSetOf()) { it.whom }
    }

    val contactMap by remember {
        contactMapFlow(
            db.contacts().stream(),
            db.clubs().stream(),
            db.groups().streamGroups(),
            db.groups().streamChannelGroups(),
        )
    }.collectAsState(initial = ContactMap.EMPTY)

    // The chip drives [AiSettings.Feature.SemanticSearch] directly so
    // the user's choice persists across navigation (the previous
    // screen-local var smartMode reset to false on every mount, which
    // looked like a regression — toggle the chip, leave Search, come
    // back, chip is off again). Show the chip whenever the platform
    // can do smart search; selected state is the persistent flag.
    val smartChipAvailable = embedder != null
    val smartMode = embedder != null && aiState.semanticSearchEnabled
    val highlightsEnabled = embedder != null && aiState.importantMessagesEnabled

    val indexProgress by (embedder?.progress?.collectAsState()
        ?: remember { mutableStateOf(io.nisfeb.talon.ai.IndexProgress()) })

    val semanticResults = remember { mutableStateOf<List<MessageEntity>>(emptyList()) }
    var semanticBusy by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(debouncedTrimmed, smartMode, embedder) {
        if (!smartMode || debouncedTrimmed.length < 2 || embedder == null) {
            semanticResults.value = emptyList()
            return@LaunchedEffect
        }
        semanticBusy = true
        semanticResults.value = runCatching { embedder.semanticSearch(debouncedTrimmed) }
            .getOrElse { emptyList() }
        semanticBusy = false
    }

    val highlights = remember { mutableStateOf<List<MessageEntity>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(highlightsEnabled, indexProgress.indexed, embedder) {
        highlights.value = if (!highlightsEnabled || embedder == null) emptyList()
        else runCatching { embedder.computeHighlights() }.getOrElse { emptyList() }
    }

    // Indexer start is owned by App.kt now — it kicks off as soon as
    // any embedder-dependent feature is enabled (smart search / topic
    // clusters / important messages), not on first SearchScreen mount.

    val results = if (smartMode) semanticResults.value else substringResults

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
        // Smart-mode toggle + index status. Render whenever the
        // platform supplies an embedder (Android on-device ML, desktop
        // DJL). Toggling the chip flips AiSettings.SemanticSearch so
        // the choice survives navigation, and App.kt's LaunchedEffect
        // wakes the indexer the moment the flag goes true.
        if (smartChipAvailable) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilterChip(
                    selected = smartMode,
                    onClick = {
                        aiSettings.setFeature(
                            io.nisfeb.talon.ai.AiSettings.Feature.SemanticSearch,
                            !smartMode,
                        )
                    },
                    label = { Text(if (smartMode) "✨ Smart on" else "✨ Smart") },
                )
                if (smartMode) {
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
                        PersonRow(
                            contact = p,
                            hasChatHistory = p.ship in activeWhomSet,
                            onClick = { onOpenConversation(p.ship) },
                        )
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
private fun PersonRow(
    contact: ContactEntity,
    hasChatHistory: Boolean,
    onClick: () -> Unit,
) {
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
            // Subtitle: real patp when we showed a nickname above, or
            // a "tap to start" hint when this contact has never shown
            // up in unreads (so DmChatScreen wouldn't be picking up an
            // existing conversation — first send creates the DM).
            val showShipSubtitle =
                !contact.nickname.isNullOrBlank() && contact.nickname != contact.ship
            when {
                showShipSubtitle -> Text(
                    contact.ship,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                !hasChatHistory -> Text(
                    "Tap to start a DM",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            // If the contact has both a nickname AND no chat history,
            // we still want the hint somewhere; tuck it under the patp.
            if (showShipSubtitle && !hasChatHistory) {
                Text(
                    "Tap to start a DM",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
