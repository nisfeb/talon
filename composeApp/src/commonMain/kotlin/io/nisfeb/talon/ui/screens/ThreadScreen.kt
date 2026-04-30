// Diverges from production in two spots:
//   1. android.util.Log.w → io.nisfeb.talon.util.Log.w (commonMain shim).
//   2. Production's onLinkTap launches an Android VIEW Intent. Desktop
//      will need a `Desktop.getDesktop().browse(URI(...))` actual; for
//      now this is a TODO placeholder, mirroring DmChatScreen.
// Keep in sync with production until app/ is removed in Stage F.
package io.nisfeb.talon.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import io.nisfeb.talon.ui.combinedClickableWithSecondary
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.ReactionEntity
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.EmojiCatalog
import io.nisfeb.talon.ui.EmojiPickerDropdown
import io.nisfeb.talon.ui.MentionPicker
import io.nisfeb.talon.ui.ReactionPalette
import io.nisfeb.talon.ui.StoryRenderer
import io.nisfeb.talon.ui.contactMapFlow
import io.nisfeb.talon.ui.detectEmojiQuery
import io.nisfeb.talon.ui.detectMentionQuery
import io.nisfeb.talon.ui.suggestionsFor
import io.nisfeb.talon.urbit.StoryCache
import io.nisfeb.talon.urbit.TlonChatRepo
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
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
    /** When non-null, the screen anchors its initial scroll on this
     *  reply id rather than the default "newest" position. Consumed
     *  once, so re-entering the thread later goes back to default. */
    initialScrollReplyId: String? = null,
    onScrollConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val parent by remember(whom, parentId) {
        db.messages().streamOne(whom, parentId).distinctUntilChanged()
    }.collectAsState(initial = null)

    val rows by remember(whom, parentId) {
        combine(
            db.messages().streamReplies(whom, parentId).distinctUntilChanged(),
            db.reactions().stream(whom).distinctUntilChanged(),
        ) { replies, reactions ->
            val byPost = reactions.groupBy { it.postId }
            val parentReacts = byPost[parentId].orEmpty()
            var prev: MessageEntity? = null
            val replyRows = ArrayList<ReplyRow>(replies.size)
            for (m in replies) {
                val showHeader = prev == null ||
                    prev!!.author != m.author ||
                    (m.sentMs - prev!!.sentMs) > THREAD_GROUP_GAP_MS
                prev = m
                replyRows.add(
                    ReplyRow(
                        m = m,
                        reactions = byPost[m.id].orEmpty(),
                        showHeader = showHeader,
                    )
                )
            }
            parentReacts to (replyRows as List<ReplyRow>)
        }.flowOn(Dispatchers.Default)
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
    LaunchedEffect(whom, parentId) {
        runCatching { repo.fetchThread(whom, parentId) }
            .onFailure { Log.w("ThreadScreen", "fetchThread failed: ${it.message}") }
    }

    var hasAnchored by remember(parentId) { mutableStateOf(false) }
    var flashReplyId by remember(parentId) { mutableStateOf<String?>(null) }
    LaunchedEffect(rows.second.size, initialScrollReplyId) {
        val total = rows.second.size + (if (parent != null) 2 else 0)
        if (total <= 0) return@LaunchedEffect
        if (!hasAnchored && initialScrollReplyId != null) {
            val idx = rows.second.indexOfFirst { it.m.id == initialScrollReplyId }
            if (idx >= 0) {
                val parentOffset = if (parent != null) 2 else 0
                listState.scrollToItem(index = idx + parentOffset)
                hasAnchored = true
                flashReplyId = initialScrollReplyId
                onScrollConsumed()
                return@LaunchedEffect
            }
            if (rows.second.isNotEmpty()) onScrollConsumed()
        }
        if (!hasAnchored || isPinnedToBottom) {
            listState.scrollToItem(index = total - 1, scrollOffset = Int.MAX_VALUE)
            hasAnchored = true
        }
    }
    LaunchedEffect(flashReplyId) {
        if (flashReplyId != null) {
            delay(5_500)
            flashReplyId = null
        }
    }

    val scope = rememberCoroutineScope()
    var draft by remember(parentId) { mutableStateOf(TextFieldValue("")) }
    var sendError by remember(parentId) { mutableStateOf<String?>(null) }
    val emojiQuery = detectEmojiQuery(draft.text, draft.selection.start)
    val emojiSuggestions = remember(emojiQuery) {
        emojiQuery?.let { (q, _) -> EmojiCatalog.search(q, limit = 6) } ?: emptyList()
    }

    val contactList by remember {
        db.contacts().stream()
    }.collectAsState(initial = emptyList())
    val allShips = remember(parent, rows.second, contactList) {
        val set = linkedSetOf<String>()
        parent?.author?.let { set.add(it) }
        rows.second.forEach { set.add(it.m.author) }
        contactList.forEach { set.add(it.ship) }
        set.toList()
    }
    val mention = detectMentionQuery(draft.text, draft.selection.start)
    val mentionSuggestions = remember(mention, allShips, contactMap) {
        mention?.let { (q, _) -> suggestionsFor(q, contactMap, allShips) } ?: emptyList()
    }
    var pendingDelete by remember(parentId) { mutableStateOf<MessageEntity?>(null) }

    val onMentionTap: (String) -> Unit = remember(onOpenConversation) {
        { patp -> onOpenConversation(patp) }
    }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val onLinkTap: (String) -> Unit = remember(uriHandler) {
        { url -> runCatching { uriHandler.openUri(url) } }
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
            val onPollVoteHandler: (MessageEntity, List<ReactionEntity>, String) -> Unit =
                { msg, rs, emoji ->
                    val mine = rs.firstOrNull { it.author == ourPatp }?.emoji
                    scope.launch {
                        runCatching {
                            if (mine == emoji) repo.unreact(whom, msg.id)
                            else repo.react(whom, msg.id, emoji)
                        }
                    }
                }
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
                        onPollVote = onPollVoteHandler,
                        showHeader = true,
                        highlighted = true,
                        flashAmber = false,
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
                    onPollVote = onPollVoteHandler,
                    showHeader = row.showHeader,
                    highlighted = false,
                    flashAmber = row.m.id == flashReplyId,
                )
            }
        }
        HorizontalDivider()
        if (emojiSuggestions.isNotEmpty() && emojiQuery != null) {
            EmojiPickerDropdown(
                suggestions = emojiSuggestions,
                onPick = { entry ->
                    val (_, colonIdx) = emojiQuery
                    val caret = draft.selection.start
                    val before = draft.text.substring(0, colonIdx)
                    val after = draft.text.substring(caret)
                    val inserted = "${entry.glyph} "
                    val newText = before + inserted + after
                    val newCaret = before.length + inserted.length
                    draft = TextFieldValue(
                        text = newText,
                        selection = TextRange(newCaret),
                    )
                },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        if (mentionSuggestions.isNotEmpty() && mention != null) {
            MentionPicker(
                suggestions = mentionSuggestions,
                onPick = { ship ->
                    val (_, atIdx) = mention
                    val caret = draft.selection.start
                    val before = draft.text.substring(0, atIdx)
                    val after = draft.text.substring(caret)
                    val inserted = "$ship "
                    val newText = before + inserted + after
                    val newCaret = before.length + inserted.length
                    draft = TextFieldValue(
                        text = newText,
                        selection = TextRange(newCaret),
                    )
                },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
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
                    val body = draft.text.trim()
                    if (body.isEmpty()) return@IconButton
                    draft = TextFieldValue("")
                    sendError = null
                    scope.launch {
                        runCatching { repo.reply(whom, parentId, body) }
                            .onFailure { err ->
                                sendError = "reply failed: ${err.message ?: err::class.simpleName}"
                            }
                    }
                },
                enabled = draft.text.isNotBlank(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }

    pendingDelete?.let { target ->
        val isMine = target.author == ourPatp
        val isChannel = whom.startsWith("chat/")
        if (!(isMine || isChannel)) {
            pendingDelete = null
            return@let
        }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this message?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
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
                TextButton(onClick = { pendingDelete = null }) {
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
    onPollVote: (MessageEntity, List<ReactionEntity>, String) -> Unit,
    showHeader: Boolean,
    highlighted: Boolean,
    flashAmber: Boolean = false,
) {
    val parts = remember(m.id, m.contentJson) { StoryCache.partsFor(m.id, m.contentJson) }
    val stamp = remember(m.sentMs) { TIME_FORMAT.format(Date(m.sentMs)) }
    val authorLabel = remember(m.author, contactMap) { contactMap.displayName(m.author) }
    val grouped = remember(reactions) {
        reactions.groupBy { it.emoji }
            .map { (emoji, rs) -> Triple(emoji, rs.size, rs.any { it.author == ourPatp }) }
    }
    val flashAlpha = remember(m.id) { Animatable(0f) }
    LaunchedEffect(flashAmber) {
        if (flashAmber) {
            flashAlpha.snapTo(1f)
            flashAlpha.animateTo(0f, tween(5_000, easing = LinearEasing))
        }
    }
    val baseColor = if (highlighted) MaterialTheme.colorScheme.surfaceVariant
        else MaterialTheme.colorScheme.surface
    val flashOverlay = Color(0xFFFFC107).copy(alpha = 0.30f * flashAlpha.value)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickableWithSecondary(onClick = {}, onLongClick = { onLongPress(m) })
            .background(baseColor)
            .background(flashOverlay)
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
            reactions = reactions,
            ourPatp = ourPatp,
            onPollVote = { emoji -> onPollVote(m, reactions, emoji) },
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

@Immutable
private data class ReplyRow(
    val m: MessageEntity,
    val reactions: List<ReactionEntity>,
    val showHeader: Boolean,
)
