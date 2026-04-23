package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.ReactionEntity
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.ReactionPalette
import io.nisfeb.talon.ui.StoryRenderer
import io.nisfeb.talon.ui.contactMapFlow
import io.nisfeb.talon.urbit.StoryCache
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ThreadScreen(
    db: AppDatabase,
    repo: TlonChatRepo,
    ourPatp: String,
    whom: String,
    parentId: String,
    onBack: () -> Unit,
    onOpenConversation: (whom: String) -> Unit,
    onOpenImage: (url: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val parent by remember(whom, parentId) {
        db.messages().streamOne(whom, parentId)
    }.collectAsState(initial = null)

    val rows by remember(whom, parentId) {
        combine(
            db.messages().streamReplies(whom, parentId),
            db.reactions().stream(whom),
        ) { replies, reactions ->
            val byPost = reactions.groupBy { it.postId }
            val parentReacts = byPost[parentId].orEmpty()
            // Collapse same-author replies within 5 min into a single
            // visual block, exactly like the chat screen.
            var prev: MessageEntity? = null
            val replyRows = replies.map { m ->
                val showHeader = prev == null ||
                    prev!!.author != m.author ||
                    (m.sentMs - prev!!.sentMs) > THREAD_GROUP_GAP_MS
                prev = m
                ReplyRow(
                    m = m,
                    reactions = byPost[m.id].orEmpty(),
                    showHeader = showHeader,
                )
            }
            parentReacts to replyRows
        }
    }.collectAsState(initial = emptyList<ReactionEntity>() to emptyList<ReplyRow>())

    val contactMap by remember {
        contactMapFlow(
            db.contacts().stream(),
            db.clubs().stream(),
            db.groups().streamGroups(),
            db.groups().streamChannelGroups(),
        )
    }.collectAsState(initial = ContactMap.EMPTY)

    val listState = rememberLazyListState()
    val isPinnedToBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            if (info.totalItemsCount == 0) return@derivedStateOf true
            val lastVisible = info.visibleItemsInfo.lastOrNull()
                ?: return@derivedStateOf false
            lastVisible.index == info.totalItemsCount - 1 &&
                (lastVisible.offset + lastVisible.size) <= info.viewportEndOffset
        }
    }
    var hasAnchored by remember(parentId) { mutableStateOf(false) }
    LaunchedEffect(rows.second.size) {
        val total = rows.second.size + (if (parent != null) 2 else 0)
        if (total <= 0) return@LaunchedEffect
        if (!hasAnchored || isPinnedToBottom) {
            listState.scrollToItem(index = total - 1, scrollOffset = Int.MAX_VALUE)
            hasAnchored = true
        }
    }

    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var draft by remember(parentId) { mutableStateOf("") }
    var sendError by remember(parentId) { mutableStateOf<String?>(null) }
    var pendingDelete by remember(parentId) { mutableStateOf<MessageEntity?>(null) }

    val onMentionTap: (String) -> Unit = remember(onOpenConversation) {
        { patp -> onOpenConversation(patp) }
    }
    val onLinkTap: (String) -> Unit = remember(context) {
        { href ->
            runCatching {
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(href),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
    }
    val onImageTap: (String) -> Unit = remember(onOpenImage) {
        { url -> onOpenImage(url) }
    }
    val onCitationTap: (String) -> Unit = remember(onOpenConversation) {
        { target -> onOpenConversation(target) }
    }

    Column(modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Thread",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        HorizontalDivider()
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            parent?.let { p ->
                item(key = "__parent") {
                    ThreadMessage(
                        m = p,
                        reactions = rows.first,
                        ourPatp = ourPatp,
                        contactMap = contactMap,
                        onMentionTap = onMentionTap,
                        onLinkTap = onLinkTap,
                        onImageTap = onImageTap,
                        onCitationTap = onCitationTap,
                        onLongPress = { pendingDelete = it },
                        showHeader = true,
                        highlighted = true,
                    )
                }
                item(key = "__parent_divider") { HorizontalDivider() }
            }
            items(
                items = rows.second,
                key = { it.m.id },
                contentType = { "reply" },
            ) { row ->
                ThreadMessage(
                    m = row.m,
                    reactions = row.reactions,
                    ourPatp = ourPatp,
                    contactMap = contactMap,
                    onMentionTap = onMentionTap,
                    onLinkTap = onLinkTap,
                    onImageTap = onImageTap,
                    onCitationTap = onCitationTap,
                    onLongPress = { pendingDelete = it },
                    showHeader = row.showHeader,
                    highlighted = false,
                )
            }
        }
        HorizontalDivider()
        if (sendError != null) {
            Text(
                sendError!!,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Reply") },
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    val body = draft.trim()
                    if (body.isEmpty()) return@IconButton
                    draft = ""
                    sendError = null
                    scope.launch {
                        runCatching { repo.reply(whom, parentId, body) }
                            .onFailure { err ->
                                sendError = "reply failed: ${err.message ?: err::class.simpleName}"
                            }
                    }
                },
                enabled = draft.isNotBlank(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }

    // Long-press → confirm → delete. Only offered for the user's own
    // messages and for any channel message (server enforces admin
    // permission and drops unauthorized pokes).
    pendingDelete?.let { target ->
        val isMine = target.author == ourPatp
        val isChannel = whom.startsWith("chat/")
        if (!(isMine || isChannel)) {
            pendingDelete = null
            return@let
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this message?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val t = target
                    pendingDelete = null
                    scope.launch {
                        runCatching {
                            repo.delete(whom, t.id, parentId = t.parentId)
                        }.onFailure {
                            sendError = "delete failed: ${it.message ?: it::class.simpleName}"
                        }
                    }
                }) {
                    Text(
                        if (isMine) "Delete" else "Delete (admin)",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ThreadMessage(
    m: MessageEntity,
    reactions: List<ReactionEntity>,
    ourPatp: String,
    contactMap: ContactMap,
    onMentionTap: (String) -> Unit,
    onLinkTap: (String) -> Unit,
    onImageTap: (String) -> Unit,
    onCitationTap: (String) -> Unit,
    onLongPress: (MessageEntity) -> Unit,
    showHeader: Boolean,
    highlighted: Boolean,
) {
    val parts = remember(m.id, m.contentJson) { StoryCache.partsFor(m.id, m.contentJson) }
    val stamp = remember(m.sentMs) { TIME_FORMAT.format(Date(m.sentMs)) }
    val authorLabel = remember(m.author, contactMap) { contactMap.displayName(m.author) }
    val grouped = remember(reactions) {
        reactions.groupBy { it.emoji }
            .map { (emoji, rs) -> Triple(emoji, rs.size, rs.any { it.author == ourPatp }) }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = { onLongPress(m) })
            .background(
                if (highlighted) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface
            )
            .padding(top = if (showHeader) 10.dp else 2.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (showHeader) {
            Text(
                "$authorLabel · $stamp",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StoryRenderer(
            parts,
            onMentionTap = onMentionTap,
            onLinkTap = onLinkTap,
            onImageTap = onImageTap,
            onCitationTap = onCitationTap,
        )
        if (grouped.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                grouped.forEach { (emoji, count, mine) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (mine) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(ReactionPalette.display(emoji), style = MaterialTheme.typography.bodyMedium)
                        if (count > 1) {
                            Text(
                                " $count",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val TIME_FORMAT = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())

private const val THREAD_GROUP_GAP_MS = 5L * 60_000L

@androidx.compose.runtime.Immutable
private data class ReplyRow(
    val m: MessageEntity,
    val reactions: List<ReactionEntity>,
    val showHeader: Boolean,
)
