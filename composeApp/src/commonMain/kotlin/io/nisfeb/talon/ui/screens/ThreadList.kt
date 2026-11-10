package io.nisfeb.talon.ui.screens
import io.nisfeb.talon.ui.ChatBackdrop
import androidx.compose.foundation.border
import io.nisfeb.talon.util.formatMonthDayClock

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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import io.nisfeb.talon.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.ReactionEntity
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.EmojiCatalog
import io.nisfeb.talon.ui.ReactionPalette
import io.nisfeb.talon.ui.StoryRenderer
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
    io.nisfeb.talon.notify.ClearNotificationsWhileShown(whom)
    // Taken once per thread: set by whatever opened it to reply.
    val openedToReply = remember(parentId) { ThreadOpenIntent.take(parentId) }
    // The parent and its replies are one state, changed in one write. As
    // two, a fetch that stored both could leave the list drawn with only
    // one of them: the second state landed in the same instant and the
    // list's contents never caught up with it.
    val thread by remember(whom, parentId) {
        combine(
            db.messages().streamOne(whom, parentId).distinctUntilChanged(),
            db.messages().streamReplies(whom, parentId).distinctUntilChanged(),
            db.reactions().stream(whom).distinctUntilChanged(),
        ) { parent, replies, reactions ->
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
            parent to (parentReacts to (replyRows as List<ReplyRow>))
        }.flowOn(Dispatchers.Default)
    }.collectAsState(initial = null to (emptyList<ReactionEntity>() to emptyList<ReplyRow>()))
    val parent = thread.first
    val rows = thread.second

    val contactMap by io.nisfeb.talon.ui.rememberContactMap(db)

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
    // Backfill the parent post when it isn't local yet. A notification
    // tap can open a thread whose parent was never synced, and on a cold
    // start the SSE channel isn't up yet — fetchThread is a silent no-op
    // until it connects. Retry until the parent lands, so the thread
    // doesn't render empty ("a thread that doesn't exist") for good.
    // ponytail: 30 one-second tries covers the connect+bootstrap window;
    // raise the cap if very slow ships still miss it.
    LaunchedEffect(whom, parentId) {
        var tries = 0
        while (db.messages().getOne(whom, parentId) == null && tries < 30) {
            runCatching { repo.fetchThread(whom, parentId) }
                .onFailure { Log.w("ThreadScreen", "fetchThread failed: ${it.message}") }
            tries++
            delay(1000)
        }
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
    LaunchedEffect(whom, parentId) {
        if (!threadDividerResolved) {
            val row = db.threadUnreads().getOne(whom, parentId)
            threadUnreadSnapshot = row?.count ?: 0
            threadDividerResolved = true
        }
        // Focus the thread: reads it now (locally and on the ship),
        // keeps replies that land while it is open read, and reads it
        // once more on the way out.
        repo.setOpenThread(whom, parentId)
    }
    DisposableEffect(whom, parentId) {
        onDispose { repo.setOpenThread(null, null) }
    }

    var hasAnchored by remember(parentId) { mutableStateOf(false) }
    var flashReplyId by remember(parentId) { mutableStateOf<String?>(null) }
    // The item count the last follow decision saw. "Pinned to bottom"
    // has to be judged against the list as it was BEFORE a reply was
    // appended: once the new row exists the old last row is no longer
    // the last item, so the live check always says "not at bottom".
    var knownTotal by remember(parentId) { mutableStateOf(0) }
    LaunchedEffect(rows.second.size, initialScrollReplyId) {
        val total = rows.second.size + (if (parent != null) 2 else 0)
        if (total <= 0) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()
        val wasPinned = lastVisible == null || lastVisible.index >= knownTotal - 1
        val ownReply = total > knownTotal && rows.second.lastOrNull()?.m?.author == ourPatp
        knownTotal = total
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
        if (!hasAnchored || wasPinned || ownReply) {
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
    // It is also the thread surface's quote slot: the main composer of
    // the same conversation keeps its own, so neither surface's
    // PendingQuotes effect can resurrect a quote the other consumed.
    val threadDraftKey = remember(whom, parentId) { "thread:$whom#$parentId" }
    val composerState = io.nisfeb.talon.ui.rememberComposerState(threadDraftKey, drafts, quoteKey = threadDraftKey)

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
                caption: String,
            ) {
                repo.replyImage(whom, parentId, src, width, height, alt, caption)
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
        io.nisfeb.talon.urbit.isDirect(whom) || isChannelNest(whom)
    }
    // Your own always; another's only where you can moderate, as in the
    // chat: offered to everyone, the ship refused it and nothing said why.
    val adminGroups by repo.adminGroupsFlow.collectAsState()
    val mayDelete: (MessageEntity) -> Boolean = { m ->
        m.author == ourPatp || (
            isChannelNest(whom) &&
                io.nisfeb.talon.urbit.canPinInGroup(ourPatp, contactMap.groupOfChannel(whom), adminGroups)
            )
    }
    var pendingDelete by remember(parentId) { mutableStateOf<MessageEntity?>(null) }
    var pendingReport by remember(parentId) { mutableStateOf<MessageEntity?>(null) }
    var editing by remember(parentId) { mutableStateOf<MessageEntity?>(null) }
    // Long-press on any thread message opens this sheet — same role
    // as DmChatScreen.actionTarget. We keep `pendingDelete` for the
    // post-confirm flow the sheet kicks off.
    var actionTarget by remember(parentId) { mutableStateOf<MessageEntity?>(null) }
    // The message whose full emoji browser is open (the quick-bar's
    // magnifier), lifted out of the per-row menu like DmChatScreen.
    var emojiPickerTarget by remember(parentId) { mutableStateOf<MessageEntity?>(null) }
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
                        if (mineSame) repo.unreact(whom, m.id, m.parentId)
                        else repo.react(whom, m.id, emoji, m.parentId)
                    }.onFailure {
                        composerState.failed("react", it)
                    }
                }
                Unit
            }
        }

    // A name or a picture opens the profile, as in the chat: the shell's
    // sheet where it has one, else the conversation as before.
    val openProfile = io.nisfeb.talon.ui.LocalOpenProfile.current
    val onMentionTap: (String) -> Unit = remember(onOpenConversation, openProfile) {
        { patp -> openProfile?.invoke(patp) ?: onOpenConversation(patp) }
    }
    val onLinkTap: (String) -> Unit = io.nisfeb.talon.ui.rememberLinkOpener()
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
        val isChannel = isChannelNest(whom)
        // Read here, not at launch: a scry per group, cached 5 minutes.
        if (isChannel) LaunchedEffect(Unit) { repo.prefetchAdminGroups() }
        val onShip = !isLocalOnly(target.id)
        ThreadActionMenu(
            db = db,
            ourPatp = ourPatp,
            canReact = onShip,
            canDelete = mayDelete(target),
            mine = isMine,
            // A chat's only: an edit to a gallery or notebook comment is
            // refused by the repo, so offering it was a promise that failed.
            canEdit = isMine && whom.startsWith("chat/") && onShip,
            canReport = !isMine && isChannel,
            onPickReaction = { code ->
                actionTarget = null
                scope.launch {
                    runCatching { repo.react(whom, target.id, code, target.parentId) }
                        .onFailure {
                            composerState.failed("react", it)
                        }
                }
            },
            onBrowseEmoji = {
                actionTarget = null
                emojiPickerTarget = target
            },
            onCopy = {
                actionTarget = null
                val text = StoryCache.copyTextFor(target.id, target.contentJson)
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
            onReport = {
                actionTarget = null
                pendingReport = target
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
    // The in-thread "New" divider stays while the thread is open, as the
    // channel's does: no timer takes it away while it is being read.

    // Follow it or stop, from inside: the same switch as on the post, so
    // its replies count (tint, notify, the Threads lists) or do not.
    val followHere by remember(whom, parentId) {
        db.followedThreads().streamForWhom(whom).map { rows -> rows.firstOrNull { it.parentPostId == parentId }?.follow }
    }.collectAsState(initial = null)
    val following = io.nisfeb.talon.urbit.threadCounts(
        followHere, io.nisfeb.talon.urbit.isDirect(whom), parent?.author == ourPatp,
    )

    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Text(
                if (following) "You follow this thread" else "You don't follow this thread",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            io.nisfeb.talon.ui.TextButton(onClick = {
                scope.launch {
                    runCatching { repo.setFollow(whom, parentId, !following) }
                        .onFailure { composerState.failed("follow", it) }
                }
            }) { Text(if (following) "Unfollow" else "Follow") }
        }
        // Bubbles or rows, as the owner set for chats; the book picks the
        // contacts' colour.
        val chatLook = io.nisfeb.talon.ui.LocalChatLook.current
        val book by repo.bookContacts.collectAsState()
        val kindOf: (String) -> io.nisfeb.talon.ui.BubbleKind = { io.nisfeb.talon.ui.bubbleKindOf(it, ourPatp, book) }
        // The owner's picture behind the bubbles and under the composer,
        // as in the chat.
        androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxWidth()) {
        ChatBackdrop(chatLook)
        Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
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
                            if (same) repo.unreact(whom, msg.id, msg.parentId)
                            else repo.react(whom, msg.id, emoji, msg.parentId)
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
                        http = http,
                        onAvatarTap = onMentionTap,
                        menuExpanded = actionTarget?.id == p.id,
                        onMenuExpand = { actionTarget = p },
                        onMenuDismiss = { actionTarget = null },
                        actionMenu = { threadActionMenuFor(p) },
                        onReactionTap = onReactionForMessage,
                        onReactionLongPress = { reactionDetailsTarget = it },
                        onPollVote = onPollVoteHandler,
                        showHeader = true,
                        highlighted = true,
                        bubbleKind = kindOf(p.author),
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
                    io.nisfeb.talon.ui.UnreadDividerRow()
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
                    http = http,
                    onAvatarTap = onMentionTap,
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
                    bubbleKind = kindOf(replyMsg.author),
                )
            }
        }
        // On a picture the composer floats on it, with no rule above.
        if (chatLook.image == null) HorizontalDivider()
        io.nisfeb.talon.ui.ChatComposer(
            state = composerState,
            db = db,
            repo = repo,
            http = http,
            drafts = drafts,
            focusOnOpen = !io.nisfeb.talon.ui.hasSoftKeyboard || openedToReply,
            whom = threadDraftKey,
            quoteKey = threadDraftKey,
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
        } // the column over the picture
        } // the picture's box
    }

    pendingDelete?.let { target ->
        val isMine = target.author == ourPatp
        if (!mayDelete(target)) {
            pendingDelete = null
            return@let
        }
        DeleteMessageDialog(
            mine = isMine,
            onDelete = {
                pendingDelete = null
                scope.launch {
                    runCatching { repo.delete(whom, target.id, parentId = target.parentId) }
                        .onFailure { composerState.failed("delete", it) }
                }
            },
            onDismiss = { pendingDelete = null },
        )
    }

    pendingReport?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingReport = null },
            title = { Text("Report message?") },
            text = {
                Text(
                    "Sends this message to the group's admins for review. " +
                        "They'll see who reported it.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = target
                    pendingReport = null
                    val flag = contactMap.groupOfChannel(whom)
                    if (flag == null) {
                        composerState.sendError = "report failed: no group for this channel"
                    } else {
                        scope.launch {
                            runCatching {
                                repo.reportMessage(flag, whom, t.id, t.parentId)
                            }.onSuccess {
                                composerState.sendError = "Reported to the group's admins"
                            }.onFailure {
                                composerState.failed("report", it)
                            }
                        }
                    }
                }) { Text("Report") }
            },
            dismissButton = {
                TextButton(onClick = { pendingReport = null }) {
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

    emojiPickerTarget?.let { target ->
        val frequent by remember {
            db.reactionUsage().streamTop(16)
        }.collectAsState(initial = emptyList())
        io.nisfeb.talon.ui.ReactionPickerSheet(
            frequent = frequent.map { it.shortcode },
            onPick = { code ->
                emojiPickerTarget = null
                scope.launch {
                    runCatching { repo.react(whom, target.id, code, target.parentId) }
                        .onFailure { composerState.failed("react", it) }
                }
            },
            onDismiss = { emojiPickerTarget = null },
        )
    }

    editing?.let { target ->
        // Open until the ship has the edit, then closed; a failed one stays
        // open with its words and says why. Closed at once, a failure threw
        // the changed words away.
        var saving by remember(target.id) { mutableStateOf(false) }
        var failure by remember(target.id) { mutableStateOf<io.nisfeb.talon.util.Problem?>(null) }
        EditThreadMessageDialog(
            // Editable text only — a quoted post's cite (and images /
            // link previews) ride along untouched via originalContentJson.
            initial = io.nisfeb.talon.urbit.editableText(target.contentJson),
            saving = saving,
            failure = failure,
            onDismiss = { editing = null },
            onSave = { newText ->
                saving = true
                failure = null
                scope.launch {
                    // parentId == null on the thread's parent row;
                    // non-null on the replies. The repo handles both, on
                    // its own scope, so leaving the thread keeps the write.
                    io.nisfeb.talon.util.runSuspendCatching {
                        repo.edit(
                            whom = whom,
                            postId = target.id,
                            text = newText,
                            originalSentMs = target.sentMs,
                            parentId = target.parentId,
                            originalContentJson = target.contentJson,
                        )
                    }.onSuccess { if (editing?.id == target.id) editing = null }
                        .onFailure { failure = io.nisfeb.talon.util.problemOf("Couldn't save the edit", it) }
                    saving = false
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
    /** The edit is on its way: Save waits, and says so. */
    saving: Boolean,
    /** Why the last save did not land, shown under the words kept for another try. */
    failure: io.nisfeb.talon.util.Problem?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var value by remember {
        // Caret at the end, so an edit continues where the text stops.
        mutableStateOf(TextFieldValue(initial, selection = TextRange(initial.length)))
    }
    // Bring the keyboard up as the dialog appears.
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit message") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
                failure?.let { io.nisfeb.talon.ui.ProblemLine(it) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(value.text.trim()) },
                enabled = value.text.isNotBlank() && !saving,
            ) {
                Text(if (saving) "Saving…" else "Save")
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
    /** For the link cards under a message, as the chat's. */
    http: io.ktor.client.HttpClient,
    onAvatarTap: (String) -> Unit,
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
    /** Whose words, for the bubble's colour. */
    bubbleKind: io.nisfeb.talon.ui.BubbleKind = io.nisfeb.talon.ui.BubbleKind.Theirs,
) {
    // Bubbles or rows, as chats are (the shells provide it).
    val look = io.nisfeb.talon.ui.LocalChatLook.current
    val bubbles = look.bubbles
    val onRight = bubbles && bubbleKind == io.nisfeb.talon.ui.BubbleKind.Mine
    val clock = remember(m.sentMs) { io.nisfeb.talon.util.formatClock(m.sentMs) }
    val parts = remember(m.id, m.contentJson) { StoryCache.partsFor(m.id, m.contentJson) }
    val stamp = remember(m.sentMs) { formatMonthDayClock(m.sentMs) }
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
    // In bubbles the rows let the chat's picture through, the parent's
    // too: its tint drew a square behind its bubble (sneagan, 2026-11-07).
    // The rule below it sets it apart there; in rows the tint does.
    val baseColor = when {
        bubbles -> Color.Transparent
        highlighted -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }
    val flashOverlay = Color(0xFFFFC107).copy(alpha = 0.30f * flashAlpha.value)
    // Desktop only: track row hover so the trailing "⋯" can reveal on
    // hover (matches DmChatScreen.MessageRow).
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val tapOpens = LocalTapOpensMenu.current
    val tapAt = remember { TapPoint() }
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
                if (tapOpens) {
                    Modifier.tapPoint(tapAt).clickable { onMenuExpand() }
                } else Modifier,
            )
            .onSecondaryClick { onMenuExpand() }
            .background(baseColor)
            .background(flashOverlay)
            // Accent tint while this message's menu is open, plus a subtle
            // hover tint (desktop) so it's clear which message the "⋯" acts on.
            .background(
                when {
                    // In bubbles the row is the window's width, and a tint
                    // across all of it said little: the "⋯" beside the bubble
                    // says which message the pointer is on (sneagan).
                    bubbles -> Color.Transparent
                    menuExpanded -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    hovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                    else -> Color.Transparent
                },
            )
            .padding(top = if (showHeader) 10.dp else 2.dp, bottom = 2.dp),
        // Spaced as the chat's row, for the picture beside the message.
        horizontalArrangement = when {
            !bubbles -> Arrangement.spacedBy(10.dp)
            onRight -> Arrangement.End
            else -> Arrangement.spacedBy(8.dp)
        },
    ) {
        if (!onRight) MessageAvatar(m.author, contactMap, showHeader) { onAvatarTap(m.author) }
        if (onRight) MessageActionsDots(hovered, menuExpanded, onMenuExpand, Modifier.align(Alignment.Top))
        Column(
            modifier = if (bubbles) Modifier.weight(1f, fill = false).widthIn(max = 560.dp) else Modifier.weight(1f),
            horizontalAlignment = if (onRight) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (showHeader && !bubbles) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        contactMap.byline(m.author, authorLabel, stamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (m.status == "pending") SendingIcon()
                }
            }
            // Only a post that could run long is measured for a fold.
            val body: @Composable () -> Unit = {
            StoryRenderer(
                parts,
                onMentionTap = onMentionTap,
                onLinkTap = onLinkTap,
                onImageTap = onImageTap,
                reactions = reactions,
                ourPatp = ourPatp,
                // A poll the ship has not taken yet has no post to vote on.
                onPollVote = if (isLocalOnly(m.id)) null else { emoji -> onPollVote(m, reactions, emoji) },
                // Touch only: null on desktop so a left-press-drag selects
                // text rather than opening the menu (hover "⋯" opens it).
                onMessageTap = if (tapOpens) onMenuExpand else null,
            )
            }
            val folded: @Composable () -> Unit = {
                if (remember(parts) { io.nisfeb.talon.ui.mightFold(parts) }) {
                    io.nisfeb.talon.ui.FoldLongPost(m.id, hasMedia = remember(parts) { io.nisfeb.talon.ui.hasMedia(parts) }, content = body)
                } else body()
            }
            if (bubbles) {
                io.nisfeb.talon.ui.MessageBubble(bubbleKind) {
                    val quiet = androidx.compose.material3.LocalContentColor.current.copy(alpha = 0.72f)
                    // A thread is many people's: each run of theirs is named.
                    if (showHeader && bubbleKind != io.nisfeb.talon.ui.BubbleKind.Mine) {
                        Text(
                            contactMap.byline(m.author, authorLabel, "").removeSuffix(" · "),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                            color = quiet,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                    folded()
                    Row(
                        modifier = Modifier.align(Alignment.End).padding(top = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(clock, style = MaterialTheme.typography.labelSmall, color = quiet)
                        if (m.status == "pending") SendingIcon()
                    }
                }
            } else folded()
            SendStateNote(m.status)
            MessageLinkCards(parts, http, onLinkTap)
            if (grouped.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // The chat's own chip, so the thread gets what it gets
                    // (the names on hover were missing from a copy here).
                    grouped.forEach { (emoji, count, mine) ->
                        ReactionChip(
                            emoji = emoji,
                            count = count,
                            mine = mine,
                            reactors = io.nisfeb.talon.ui.reactorsLine(
                                reactions.filter { ReactionPalette.normalize(it.emoji) == emoji }.map { it.author },
                                ourPatp,
                            ) { contactMap.displayName(it) },
                            onClick = { onReactionTap(m, reactions, emoji) },
                            onLongClick = { onReactionLongPress(reactions) },
                        )
                    }
                }
            }
            // Action menu. Opened by tapping the row on touch, at the
            // finger, and by the hover "⋯" below on desktop, anchored to
            // the message.
            if (tapOpens) {
                if (menuExpanded) TouchMenuPopup(tapAt, onMenuDismiss) { actionMenu() }
            } else androidx.compose.material3.DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = onMenuDismiss,
                // Narrower, and set off from the chat behind it: its own
                // raised colour and a hairline edge, as the menu's own (on
                // its inner column they lost the menu's rounded corners).
                modifier = Modifier.widthIn(min = 200.dp, max = 260.dp),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                actionMenu()
            }
        }
        if (!onRight) MessageActionsDots(hovered, menuExpanded, onMenuExpand, Modifier.align(Alignment.Top))
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
    /** False for a reply the ship has not taken yet: there is nothing there to react to. */
    canReact: Boolean,
    canDelete: Boolean,
    /** Our own: Delete says so, and another's says it is an admin's. */
    mine: Boolean,
    canEdit: Boolean,
    canReport: Boolean,
    onPickReaction: (String) -> Unit,
    /** Open the full emoji browser (the quick-bar's magnifier). */
    onBrowseEmoji: () -> Unit,
    onCopy: () -> Unit,
    onCopyMarkdown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReport: () -> Unit,
) {
    val copyText = MenuAct("Copy text", onCopy)
    val copyMarkdown = MenuAct("Copy as Markdown", onCopyMarkdown)
    val edit = MenuAct("Edit", onEdit).takeIf { canEdit }
    val report = MenuAct("Report", onReport, danger = true).takeIf { canReport }
    val delete = MenuAct(if (mine) "Delete" else "Delete (admin)", onDelete, danger = true).takeIf { canDelete }
    LocalTouchMenuSide.current?.let { side ->
        TouchMenu(
            side,
            entries = listOfNotNull(edit) + group("Copy", listOf(copyText, copyMarkdown)) + listOfNotNull(report, delete),
            reactions = if (canReact) ({ TouchReactions(db, onPickReaction, onBrowseEmoji) }) else null,
        )
        return
    }
    val itemPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    val itemMinHeight = 36.dp
    Column(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
            if (canReact) {
                // Suggested row blends the user's most-used reactions with
                // the default palette — same blend DmChatScreen uses, so
                // the thread sheet feels consistent with the chat sheet.
                val suggested = rememberSuggestedReactions(db, 8)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "React",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp),
                    )
                    io.nisfeb.talon.ui.IconButton(tip = "Browse emojis", onClick = onBrowseEmoji,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = "Browse emojis",
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
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }
            // The menu's own ink, not the button's.
            val ink = LocalContentColor.current
            listOfNotNull(copyText, copyMarkdown, edit, report, delete).forEach { act ->
                TextButton(
                    onClick = act.onClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = itemMinHeight),
                    contentPadding = itemPadding,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        act.label,
                        color = if (act.danger) MaterialTheme.colorScheme.error else ink,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
    }
}

/** A %channels nest: chat, gallery or notebook. Comments on a gallery
 *  or notebook post ride the same reply shape as a chat thread, so the
 *  thread screen serves all three. */
private fun isChannelNest(whom: String): Boolean =
    whom.startsWith("chat/") || whom.startsWith("heap/") || whom.startsWith("diary/")

/**
 * Whether the thread about to open was opened in order to reply --
 * the action sheet's Reply, or a swipe -- as opposed to being opened
 * to read. Set just before the host is asked to open it, taken once
 * by the thread as it composes. Keyed by the parent: a bare flag
 * handed the intent to whichever thread happened to compose first,
 * which is not necessarily the one the reply was aimed at.
 */
internal object ThreadOpenIntent {
    private val replies = mutableSetOf<String>()
    fun reply(parentId: String) { replies += parentId }
    fun take(parentId: String): Boolean = replies.remove(parentId)
}
