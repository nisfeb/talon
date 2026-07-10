package io.nisfeb.talon.ui.screens
import io.nisfeb.talon.util.formatMonthDayTime

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.ReactionEntity
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.EmojiCatalog
import io.nisfeb.talon.ui.ReactionPalette
import io.nisfeb.talon.ui.StoryRenderer
import io.nisfeb.talon.ui.combinedClickableWithSecondary
import io.nisfeb.talon.ui.contactMapFlow
import io.nisfeb.talon.ui.onSecondaryClick
import io.nisfeb.talon.urbit.StoryCache
import io.nisfeb.talon.urbit.TlonChatRepo
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * Body of [ThreadScreen]: the parent message + replies list, the
 * composer, and the long-press action / delete-confirm / reaction-
 * details surfaces. The screen wrapper supplies the back-arrow header
 * and `windowInsetsPadding` outside this composable.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ThreadList(
    db: AppDatabase,
    repo: TlonChatRepo,
    http: io.ktor.client.HttpClient,
    drafts: io.nisfeb.talon.ui.DraftStore,
    ourPatp: String,
    whom: String,
    parentId: String,
    initialScrollReplyId: String?,
    onScrollConsumed: () -> Unit = {},
    onOpenConversation: (whom: String) -> Unit,
    onOpenImage: (url: String) -> Unit,
    /** Android-only platform widget slots forwarded to the composer.
     *  Desktop passes null; the composer surface degrades gracefully
     *  (no mic button, no /loc, no inline voice playback). */
    voiceComposer: (@Composable (
        enabled: Boolean,
        onRecorded: (path: String, durationMs: Long) -> Unit,
    ) -> Unit)? = null,
    voicePlayer: (@Composable (path: String, sending: Boolean) -> Unit)? = null,
    locationProvider: io.nisfeb.talon.ui.LocationProvider? = null,
    onSlashMic: (() -> Unit)? = null,
    hideComposerButtons: Boolean = false,
    powerFeaturesEnabled: Boolean = false,
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

    // Snapshot the per-thread unread count at thread-open. The
    // snapshot is what positions the "New" divider in the list
    // (above the last N replies). Cleared in the local DB the moment
    // we read it so the per-row indicator on the message bubble in
    // the parent chat tints stop highlighting — server-side state
    // catches up on the next markRead(whom) at channel scope.
    var threadUnreadSnapshot by remember(whom, parentId) {
        mutableStateOf<Int?>(null)
    }
    var threadDividerResolved by remember(whom, parentId) { mutableStateOf(false) }
    // Fade trigger for the in-thread "New" divider — same dwell-fade
    // contract as the channel divider in DmChatScreen.
    var threadDividerFaded by remember(whom, parentId) { mutableStateOf(false) }
    LaunchedEffect(whom, parentId) {
        if (!threadDividerResolved) {
            val row = db.threadUnreads().getOne(whom, parentId)
            threadUnreadSnapshot = row?.count ?: 0
            threadDividerResolved = true
            if ((row?.count ?: 0) > 0) {
                runCatching { repo.markThreadReadLocal(whom, parentId) }
            }
        }
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
    // Thread drafts are scoped per (whom, parentId) so reply input
    // doesn't share state with the main chat composer or with other
    // threads in the same channel. The composer treats the namespaced
    // string as its draft key — that's all the screen passes to it.
    val threadDraftKey = remember(whom, parentId) { "thread:$whom#$parentId" }
    val composerState = io.nisfeb.talon.ui.rememberComposerState(threadDraftKey, drafts)

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

    // Reply payloads use the same wire shapes as the main composer. A
    // reply's content is a full story, so an attached image goes as a
    // structured image block and renders inline — same as a top-level
    // post. Quote-into-thread is not on the wire (`replyQuote` doesn't
    // exist), so the strategy declines and the composer falls back to
    // plain text.
    val threadStrategy = remember(repo, whom, parentId) {
        object : io.nisfeb.talon.ui.ChatSendStrategy {
            override suspend fun sendText(text: String) {
                repo.reply(whom, parentId, text)
            }
            override suspend fun sendImage(
                src: String,
                width: Int,
                height: Int,
                alt: String,
            ) {
                repo.replyImage(whom, parentId, src, width, height, alt)
            }
            override val supportsQuote: Boolean = false
            override suspend fun sendQuote(
                body: String,
                quoteWhom: String,
                quoteId: String,
            ) {
                error("thread strategy doesn't support quote")
            }
        }
    }
    val canSend = remember(whom) {
        whom.startsWith("~") || whom.startsWith("0v") || whom.startsWith("chat/")
    }
    var pendingDelete by remember(parentId) { mutableStateOf<MessageEntity?>(null) }
    var editing by remember(parentId) { mutableStateOf<MessageEntity?>(null) }
    // Long-press on any thread message opens this sheet — same role
    // as DmChatScreen.actionTarget. We keep `pendingDelete` for the
    // post-confirm flow the sheet kicks off.
    var actionTarget by remember(parentId) { mutableStateOf<MessageEntity?>(null) }
    var reactionDetailsTarget by remember(parentId) {
        mutableStateOf<List<ReactionEntity>?>(null)
    }
    val onReactionForMessage: (MessageEntity, List<ReactionEntity>, String) -> Unit =
        remember(ourPatp, whom, repo) {
            { m, rs, emoji ->
                // Compare on the canonical key (see DmChatScreen): react()
                // stores the variation-selector-stripped form, and the
                // picker may hand us a shortcode or a glyph — normalize
                // both sides so our own reaction toggles off reliably.
                val ours = ReactionPalette.normalize(emoji)
                val mineSame = rs.any {
                    it.author == ourPatp && ReactionPalette.normalize(it.emoji) == ours
                }
                scope.launch {
                    runCatching {
                        if (mineSame) repo.unreact(whom, m.id)
                        else repo.react(whom, m.id, emoji)
                    }.onFailure {
                        composerState.sendError = "react failed: ${it.message ?: it::class.simpleName}"
                    }
                }
                Unit
            }
        }

    val onMentionTap: (String) -> Unit = remember(onOpenConversation) {
        { patp -> onOpenConversation(patp) }
    }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val urbLinkHandler = io.nisfeb.talon.ui.LocalUrbLinkHandler.current
    val onLinkTap: (String) -> Unit = remember(uriHandler, urbLinkHandler) {
        { url ->
            if (io.nisfeb.talon.urbit.UrbLink.isUrbUrl(url)) urbLinkHandler(url)
            else runCatching { uriHandler.openUri(url) }
        }
    }
    val onImageTap: (String) -> Unit = remember(onOpenImage) {
        { url -> onOpenImage(url) }
    }

    val chatDensity = io.nisfeb.talon.ui.LocalChatDensity.current
    // Per-row action-menu body. Composed only when the row's menu is
    // expanded (DropdownMenu's content composes lazily). Mirrors the
    // pattern in DmChatScreen.MessageRow.
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val threadActionMenuFor: @Composable (MessageEntity) -> Unit = { target ->
        val isMine = target.author == ourPatp
        val isChannel = whom.startsWith("chat/")
        ThreadActionMenu(
            db = db,
            ourPatp = ourPatp,
            canDelete = isMine || isChannel,
            canEdit = isMine && isChannel,
            onPickReaction = { code ->
                actionTarget = null
                scope.launch {
                    runCatching { repo.react(whom, target.id, code) }
                        .onFailure {
                            composerState.sendError =
                                "react failed: ${it.message ?: it::class.simpleName}"
                        }
                }
            },
            onCopy = {
                actionTarget = null
                val text = StoryCache.textFor(target.id, target.contentJson)
                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(text))
            },
            onCopyMarkdown = {
                actionTarget = null
                val md = io.nisfeb.talon.urbit.RawMarkdown
                    .fromStoryJson(target.contentJson)
                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(md))
            },
            onEdit = {
                actionTarget = null
                editing = target
            },
            onDelete = {
                actionTarget = null
                pendingDelete = target
            },
        )
    }

    // First-unread anchor for the "New" divider, hoisted out of the
    // LazyColumn builder so the dwell-fade effect below can watch it.
    // Reply list is oldest-first → newest-last, so the last N entries
    // are the unread ones (per the snapshot captured at thread-open).
    // null when there's nothing to flag.
    val firstUnreadReplyId: String? = run {
        val n = threadUnreadSnapshot ?: 0
        if (n <= 0 || rows.second.isEmpty()) null
        else rows.second
            .getOrNull(rows.second.size - n.coerceAtMost(rows.second.size))
            ?.m
            ?.id
    }
    // Dwell-fade: same contract as the channel divider. Once the reply
    // the divider sits above has been continuously visible 5s, fade.
    LaunchedEffect(firstUnreadReplyId, whom, parentId) {
        if (firstUnreadReplyId == null || threadDividerFaded) return@LaunchedEffect
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.any { it.key == firstUnreadReplyId }
        }.collectLatest { visible ->
            if (visible) {
                delay(5_000)
                threadDividerFaded = true
            }
        }
    }

    Column(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(chatDensity.messageSpacing),
        ) {
            val onPollVoteHandler: (MessageEntity, List<ReactionEntity>, String) -> Unit =
                { msg, rs, emoji ->
                    val mine = rs.firstOrNull { it.author == ourPatp }?.emoji
                    val same = mine != null &&
                        ReactionPalette.normalize(mine) == ReactionPalette.normalize(emoji)
                    scope.launch {
                        runCatching {
                            if (same) repo.unreact(whom, msg.id)
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
                        menuExpanded = actionTarget?.id == p.id,
                        onMenuExpand = { actionTarget = p },
                        onMenuDismiss = { actionTarget = null },
                        actionMenu = { threadActionMenuFor(p) },
                        onReactionTap = onReactionForMessage,
                        onReactionLongPress = { reactionDetailsTarget = it },
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
                if (row.m.id == firstUnreadReplyId) {
                    io.nisfeb.talon.ui.UnreadDividerRow(faded = threadDividerFaded)
                }
                val replyMsg = row.m
                ThreadMessage(
                    m = replyMsg,
                    reactions = row.reactions,
                    ourPatp = ourPatp,
                    contactMap = contactMap,
                    onMentionTap = onMentionTap,
                    onLinkTap = onLinkTap,
                    onImageTap = onImageTap,
                    menuExpanded = actionTarget?.id == replyMsg.id,
                    onMenuExpand = { actionTarget = replyMsg },
                    onMenuDismiss = { actionTarget = null },
                    actionMenu = { threadActionMenuFor(replyMsg) },
                    onReactionTap = onReactionForMessage,
                    onReactionLongPress = { reactionDetailsTarget = it },
                    onPollVote = onPollVoteHandler,
                    showHeader = row.showHeader,
                    highlighted = false,
                    flashAmber = row.m.id == flashReplyId,
                )
            }
        }
        HorizontalDivider()
        io.nisfeb.talon.ui.ChatComposer(
            state = composerState,
            db = db,
            repo = repo,
            http = http,
            drafts = drafts,
            whom = threadDraftKey,
            contactMap = contactMap,
            allShips = allShips,
            canSend = canSend,
            hideComposerButtons = hideComposerButtons,
            placeholder = "Reply",
            locationProvider = locationProvider,
            voiceComposer = voiceComposer,
            voicePlayer = voicePlayer,
            onSlashMic = onSlashMic,
            powerFeaturesEnabled = powerFeaturesEnabled,
            strategy = threadStrategy,
        )
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
                            composerState.sendError = "delete failed: ${it.message ?: it::class.simpleName}"
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

    reactionDetailsTarget?.let { reactions ->
        io.nisfeb.talon.ui.ReactionDetailsSheet(
            reactions = reactions,
            contactMap = contactMap,
            onDismiss = { reactionDetailsTarget = null },
        )
    }

    editing?.let { target ->
        EditThreadMessageDialog(
            // Editable text only — a quoted post's cite (and images /
            // link previews) ride along untouched via originalContentJson.
            initial = io.nisfeb.talon.urbit.editableText(target.contentJson),
            onDismiss = { editing = null },
            onSave = { newText ->
                editing = null
                scope.launch {
                    runCatching {
                        // parentId == null on the thread's parent row;
                        // non-null on the replies. The repo handles both.
                        repo.edit(
                            whom = whom,
                            postId = target.id,
                            text = newText,
                            originalSentMs = target.sentMs,
                            parentId = target.parentId,
                            originalContentJson = target.contentJson,
                        )
                    }.onFailure {
                        composerState.sendError =
                            "edit failed: ${it.message ?: it::class.simpleName}"
                    }
                }
            },
        )
    }
}

/**
 * Minimal edit dialog for thread messages — same UX as
 * DmChatScreen's EditMessageDialog (which is private to that file).
 * Kept separate rather than promoted to shared to avoid the visual
 * churn of moving the dialog out of DmChatScreen.
 */
@Composable
private fun EditThreadMessageDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit message") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }, enabled = text.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
    /** True when this row's action menu is open (driven by the
     *  screen-level `actionTarget` so only one menu shows at a time). */
    menuExpanded: Boolean,
    onMenuExpand: () -> Unit,
    onMenuDismiss: () -> Unit,
    /** Per-row action menu body — invoked inside a [DropdownMenu]
     *  anchored to the trailing "⋯" button. See DmChatScreen's
     *  MessageRow for the equivalent slot. */
    actionMenu: @Composable () -> Unit,
    /** Called when the user taps an already-rendered reaction chip
     *  to toggle their own reaction. Same shape as DmChatScreen's
     *  onReactionTap so the handler logic can be shared. */
    onReactionTap: (MessageEntity, List<ReactionEntity>, String) -> Unit,
    /** Long-press / right-click on a reaction chip → show the
     *  per-reactor breakdown. */
    onReactionLongPress: (List<ReactionEntity>) -> Unit,
    onPollVote: (MessageEntity, List<ReactionEntity>, String) -> Unit,
    showHeader: Boolean,
    highlighted: Boolean,
    flashAmber: Boolean = false,
) {
    val parts = remember(m.id, m.contentJson) { StoryCache.partsFor(m.id, m.contentJson) }
    val stamp = remember(m.sentMs) { formatMonthDayTime(m.sentMs) }
    val authorLabel = remember(m.author, contactMap) { contactMap.displayName(m.author) }
    val grouped = remember(reactions) {
        // Normalize on read too: rows stored before we normalized on write
        // still carry FE0F, and would otherwise render as a separate chip.
        reactions.groupBy { ReactionPalette.normalize(it.emoji) }
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
    // Desktop only: track row hover so the trailing "⋯" can reveal on
    // hover (matches DmChatScreen.MessageRow).
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    // Touch: a single tap on the row opens the action menu (and tints the
    // row to show which message). On desktop that clickable is OFF so a
    // left-press-drag reaches the text's SelectionContainer instead of
    // popping the menu; desktop opens it via the hover "⋯" below.
    // (isTapToOpenMenuSupported — see DmChatScreen.MessageRow.)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource)
            .then(
                if (io.nisfeb.talon.ui.isTapToOpenMenuSupported) {
                    Modifier.clickable { onMenuExpand() }
                } else Modifier,
            )
            .onSecondaryClick { onMenuExpand() }
            .background(baseColor)
            .background(flashOverlay)
            // Accent tint while this message's menu is open, plus a subtle
            // hover tint (desktop) so it's clear which message the "⋯" acts on.
            .background(
                when {
                    menuExpanded -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    hovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                    else -> Color.Transparent
                },
            )
            .padding(top = if (showHeader) 10.dp else 2.dp, bottom = 2.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
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
                reactions = reactions,
                ourPatp = ourPatp,
                onPollVote = { emoji -> onPollVote(m, reactions, emoji) },
                // Touch only: null on desktop so a left-press-drag selects
                // text rather than opening the menu (hover "⋯" opens it).
                onMessageTap = if (io.nisfeb.talon.ui.isTapToOpenMenuSupported) onMenuExpand else null,
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
                                .combinedClickableWithSecondary(
                                    onClick = { onReactionTap(m, reactions, emoji) },
                                    onLongClick = { onReactionLongPress(reactions) },
                                )
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                ReactionPalette.display(emoji),
                                fontFamily = io.nisfeb.talon.ui.EmojiFontFamily,
                                style = MaterialTheme.typography.bodyMedium,
                            )
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
            // Action menu, anchored to the message. Opened by tapping the
            // row on touch and by the hover "⋯" below on desktop.
            androidx.compose.material3.DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = onMenuDismiss,
                modifier = Modifier.widthIn(min = 280.dp, max = 360.dp),
            ) {
                actionMenu()
            }
        }
        // Desktop affordance: a hover-revealed "⋯" — the reliable way to
        // open the menu when tap-to-open is off (selection owns left-drag;
        // SelectionContainer eats the row's right-click). A plain Icon, NOT
        // IconButton — its 48dp min touch size inflates short rows.
        // Top-aligned + alpha-toggled so the slot never shifts. Mirrors
        // DmChatScreen.MessageRow.
        if (!io.nisfeb.talon.ui.isTapToOpenMenuSupported) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = "Message actions",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.Top)
                    .alpha(if (hovered || menuExpanded) 1f else 0f)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onMenuExpand)
                    .padding(2.dp)
                    .size(20.dp),
            )
        }
    }
}


private const val THREAD_GROUP_GAP_MS = 5L * 60_000L

@Immutable
private data class ReplyRow(
    val m: MessageEntity,
    val reactions: List<ReactionEntity>,
    val showHeader: Boolean,
)

/**
 * Long-press sheet for thread messages. Same React + Search-emojis
 * vocabulary as DmChatScreen's MessageActionSheet but trimmed to the
 * actions threads actually need: react and delete (if allowed). No
 * reply / quote / bookmark / pin — reply is the current view, the
 * others don't apply to thread replies.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ThreadActionMenu(
    db: AppDatabase,
    ourPatp: String,
    canDelete: Boolean,
    canEdit: Boolean,
    onPickReaction: (String) -> Unit,
    onCopy: () -> Unit,
    onCopyMarkdown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val itemPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    val itemMinHeight = 36.dp
    Column(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
            // Suggested row blends the user's most-used reactions with
            // the default palette — same blend DmChatScreen uses, so
            // the thread sheet feels consistent with the chat sheet.
            val topUsage by remember {
                db.reactionUsage().streamTop(8)
            }.collectAsState(initial = emptyList())
            // De-dupe by canonical reaction (normalize) so a used glyph
            // and its palette code don't both show — see DmChatScreen.
            val suggested = remember(topUsage) {
                val seen = mutableSetOf<String>()
                val out = mutableListOf<String>()
                (topUsage.map { it.shortcode } + ReactionPalette.picker.map { it.first })
                    .forEach { item ->
                        if (seen.add(ReactionPalette.normalize(item))) out.add(item)
                    }
                out.take(8)
            }

            var searchOpen by remember { mutableStateOf(false) }
            var searchQuery by remember { mutableStateOf("") }
            val searchFocus = remember { FocusRequester() }
            // Auto-focus the search field when the magnifying glass
            // is tapped — Android raises the IME on focus.
            LaunchedEffect(searchOpen) {
                if (searchOpen) searchFocus.requestFocus()
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "React",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp),
                )
                IconButton(
                    onClick = {
                        searchOpen = !searchOpen
                        if (!searchOpen) searchQuery = ""
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = "Search emojis",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 4.dp),
            ) {
                suggested.forEach { code ->
                    val glyph = ReactionPalette.display(code)
                    Text(
                        glyph,
                        fontFamily = io.nisfeb.talon.ui.EmojiFontFamily,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onPickReaction(code) }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            if (searchOpen) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search emojis") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .focusRequester(searchFocus),
                )
                val results = remember(searchQuery) {
                    if (searchQuery.isBlank()) emptyList()
                    else EmojiCatalog.search(searchQuery, limit = 24)
                }
                if (results.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                    ) {
                        results.forEach { entry ->
                            Text(
                                entry.glyph,
                                fontFamily = io.nisfeb.talon.ui.EmojiFontFamily,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onPickReaction(entry.shortcode) }
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            @Composable
            fun ActionRow(
                onClick: () -> Unit,
                label: String,
                color: Color = LocalContentColor.current,
            ) {
                TextButton(
                    onClick = onClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = itemMinHeight),
                    contentPadding = itemPadding,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        label,
                        color = color,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            ActionRow(onClick = onCopy, label = "Copy text")
            ActionRow(onClick = onCopyMarkdown, label = "Copy as Markdown")
            if (canEdit) {
                ActionRow(onClick = onEdit, label = "Edit")
            }
            if (canDelete) {
                ActionRow(
                    onClick = onDelete,
                    label = "Delete",
                    color = MaterialTheme.colorScheme.error,
                )
            }
    }
}
