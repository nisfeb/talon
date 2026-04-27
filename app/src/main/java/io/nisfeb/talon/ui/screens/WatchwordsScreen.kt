package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.TalonApplication
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.WatchwordHitEntity
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.contactMapFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun WatchwordsScreen(
    db: AppDatabase,
    onBack: () -> Unit,
    onOpenConversation: (whom: String, postId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as TalonApplication

    val terms by app.watchwords.terms.collectAsState()
    val hitCounts by remember {
        db.watchwords().streamHitCountsByTerm()
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
    }.collectAsState(initial = emptyList())
    val countsByTerm = remember(hitCounts) {
        hitCounts.associate { it.term to it.cnt }
    }

    var selectedTerm by remember { mutableStateOf<String?>(null) }
    val hits by remember(selectedTerm) {
        if (selectedTerm == null)
            db.watchwords().streamAllHits()
        else
            db.watchwords().streamHitsForTerm(selectedTerm!!)
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .let { remember(selectedTerm) { it } }
        .collectAsState(initial = emptyList())

    val contactMap by remember {
        contactMapFlow(
            db.contacts().stream(),
            db.clubs().stream(),
            db.groups().streamGroups(),
            db.groups().streamChannelGroups(),
        )
    }.collectAsState(initial = ContactMap.EMPTY)

    var manageOpen by remember { mutableStateOf(false) }

    Column(modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Watchwords",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 4.dp).weight(1f),
            )
            IconButton(onClick = { manageOpen = true }) {
                Icon(Icons.Filled.Edit, contentDescription = "Manage terms")
            }
        }
        HorizontalDivider()

        if (terms.isNotEmpty()) {
            FilterChips(
                terms = terms.map { it.term },
                hitCounts = countsByTerm,
                selected = selectedTerm,
                onSelect = { selectedTerm = it },
            )
            HorizontalDivider()
        }

        when {
            terms.isEmpty() -> EmptyTerms(onAdd = { manageOpen = true })
            hits.isEmpty() -> EmptyHits(termCount = terms.size)
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(
                    items = hits,
                    key = { "${it.term}|${it.whom}|${it.postId}" },
                ) { hit ->
                    HitRow(
                        hit = hit,
                        contactMap = contactMap,
                        onClick = { onOpenConversation(hit.whom, hit.postId) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (manageOpen) {
        ManageTermsSheet(onDismiss = { manageOpen = false })
    }
}

@Composable
private fun FilterChips(
    terms: List<String>,
    hitCounts: Map<String, Int>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Chip(text = "All", selected = selected == null, onClick = { onSelect(null) })
        }
        items(terms, key = { it }) { term ->
            val count = hitCounts[term] ?: 0
            Chip(
                text = if (count > 0) "$term  $count" else term,
                selected = selected == term,
                onClick = { onSelect(term) },
            )
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        ),
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun HitRow(
    hit: WatchwordHitEntity,
    contactMap: ContactMap,
    onClick: () -> Unit,
) {
    val convoLabel = remember(hit.whom, contactMap) { contactMap.conversationLabel(hit.whom) }
    val timeLabel = remember(hit.sentMs) { DATE_FMT.format(Date(hit.sentMs)) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "${hit.term} · $convoLabel · $timeLabel",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            hit.snippet,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
        )
    }
}

@Composable
private fun EmptyTerms(onAdd: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Add a watchword and get pinged when it's mentioned anywhere.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onAdd) { Text("Add a watchword") }
        }
    }
}

@Composable
private fun EmptyHits(termCount: Int) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Watching $termCount term${if (termCount == 1) "" else "s"}. No hits yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val DATE_FMT = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())
