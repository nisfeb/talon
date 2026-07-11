package io.nisfeb.talon.ui.screens
import io.nisfeb.talon.util.formatMonthDay
import io.nisfeb.talon.util.ConcurrentMap
import io.nisfeb.talon.util.formatMonthDayTime
import io.nisfeb.talon.util.formatMonthDayYear
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import io.nisfeb.talon.util.nowMs

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
import androidx.compose.foundation.combinedClickable
import io.nisfeb.talon.ui.combinedClickableWithSecondary
import io.nisfeb.talon.ui.onSecondaryClick
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Topic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.ai.AiClient
import io.nisfeb.talon.ai.AiFeatures
import io.nisfeb.talon.ai.AiSettingsRepository
import io.nisfeb.talon.ai.kMeansAssign
import io.nisfeb.talon.ai.unpackEmbedding
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.NotifyLevel
import io.nisfeb.talon.data.ReactionEntity
import io.nisfeb.talon.data.ReactionUsageEntity
import io.nisfeb.talon.data.ReplyCount
import io.nisfeb.talon.ui.Avatar
import io.nisfeb.talon.ui.CommandResult
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.ContactProfileSheet
import io.nisfeb.talon.ui.DraftStore
import io.nisfeb.talon.ui.EmojiCatalog
import io.nisfeb.talon.ui.EmojiPickerDropdown
import io.nisfeb.talon.ui.LinkPreviewCard
import io.nisfeb.talon.ui.firstLinkUrl
import io.nisfeb.talon.ui.MentionPicker
import io.nisfeb.talon.ui.ReactionPalette
import io.nisfeb.talon.ui.SlashPicker
import io.nisfeb.talon.ui.StoryRenderer
import io.nisfeb.talon.ui.contactMapFlow
import io.nisfeb.talon.ui.detectEmojiQuery
import io.nisfeb.talon.ui.detectMentionQuery
import io.nisfeb.talon.ui.detectSlashTrigger
import io.nisfeb.talon.ui.filterSlashCommands
import io.nisfeb.talon.ui.runCommand
import io.nisfeb.talon.ui.suggestionsFor
import io.nisfeb.talon.urbit.StoryCache
import io.nisfeb.talon.urbit.TlonChatRepo
import io.nisfeb.talon.util.Log
import io.nisfeb.talon.util.decodeImageDimensions
import io.nisfeb.talon.util.rememberImagePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import io.ktor.client.HttpClient

@OptIn(
    ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
@Composable
fun DmChatScreen(
    db: AppDatabase,
    repo: TlonChatRepo,
    drafts: DraftStore,
    http: HttpClient,
    aiSettings: AiSettingsRepository,
    uiSettings: io.nisfeb.talon.ui.UiSettings,
    ourPatp: String,
    whom: String,
    initialScrollMessageId: String? = null,
    onScrollConsumed: () -> Unit = {},
    onBack: () -> Unit,
    onOpenThread: (parentId: String) -> Unit,
    onOpenThreadAt: (parentId: String, replyAnchor: String) -> Unit = { p, _ -> onOpenThread(p) },
    onOpenConversation: (whom: String) -> Unit,
    onOpenImage: (url: String) -> Unit,
    onOpenSelfProfile: () -> Unit,
    /** Optional Android-only platform widget slots. Each replaces a
     *  former expect/actual shim; desktop and tests pass null and the
     *  surface degrades gracefully (no voice button, no GPS for /loc, no
     *  voice preview playback). The platform entry point that creates
     *  App() supplies these — Main.kt stays null, the future MainActivity
     *  will pass real Composables. */
    voiceComposer: (@Composable (
        enabled: Boolean,
        onRecorded: (path: String, durationMs: Long) -> Unit,
    ) -> Unit)? = null,
    locationProvider: io.nisfeb.talon.ui.LocationProvider? = null,
    /** Inline play/pause control for the voice preview row. Android
     *  wires an ExoPlayer-backed control; desktop passes null and
     *  the preview row hides the play button (still allows send/cancel). */
    voicePlayer: (@Composable (path: String, sending: Boolean) -> Unit)? = null,
    /** Triggered when the user types `/mic` and hits send. Android
     *  wires this to start the voice recorder (same path the
     *  voiceComposer mic button uses). When null the slash command
     *  surfaces a user-facing "tap the mic button" error. */
    onSlashMic: (() -> Unit)? = null,
    /**
     * Tap handler for the new info icon in the chat header. v1 routes
     * this to the right pane on wide and to a full-screen
     * [GroupInfoScreen] on compact. Caller decides — `DmChatScreen`
     * just fires the lambda. Pass `null` from a caller that hasn't
     * wired info yet (icon stays hidden).
     */
    onOpenGroupInfo: (() -> Unit)? = null,
    /** Optional embedder client. When non-null the topic-clusters
     *  empty state surfaces live indexer progress instead of the
     *  generic "check back" placeholder. */
    searchEmbedder: io.nisfeb.talon.ai.SearchEmbedderClient? = null,
    modifier: Modifier = Modifier,
) {
    val aiConfigured by aiSettings.state.collectAsState()
    val hideComposerButtons by uiSettings.hideComposerButtons.collectAsState()
    val powerFeaturesEnabled by uiSettings.powerFeaturesEnabled.collectAsState()
    val aiFeatures = remember(aiSettings) {
        AiFeatures(AiClient { aiSettings.state.value })
    }
    var catchUpSummary by remember(whom) { mutableStateOf<String?>(null) }
    var catchingUp by remember(whom) { mutableStateOf(false) }
    var catchUpError by remember(whom) { mutableStateOf<String?>(null) }
    var topicsSheetOpen by remember(whom) { mutableStateOf(false) }
    val composerState = io.nisfeb.talon.ui.rememberComposerState(whom, drafts)
    val rows by remember(whom) {
        var prevByMsgId: Map<String, DisplayRow> = emptyMap()
        kotlinx.coroutines.flow.combine(
            db.messages().stream(whom).distinctUntilChanged(),
            db.reactions().stream(whom).distinctUntilChanged()
                .onStart { emit(emptyList()) },
            db.messages().streamReplyCounts(whom).distinctUntilChanged()
                .onStart { emit(emptyList()) },
            db.threadUnreads().streamForWhom(whom).distinctUntilChanged()
                .onStart { emit(emptyList()) },
        ) { messages, reactions, replyCounts, threadUnreads ->
            if (messages.isEmpty()) {
                prevByMsgId = emptyMap()
                emptyList()
            } else {
                val (items, nextMap) = buildChatListItemsReusing(
                    messages = messages,
                    reactsByPost = reactions.groupBy { it.postId },
                    countsByPost = replyCounts.associateBy(ReplyCount::postId),
                    threadUnreadByPost = threadUnreads
                        .associateBy { it.parentPostId },
                    prev = prevByMsgId,
                )
                prevByMsgId = nextMap
                items
            }
        }
            .onEach { items ->
                items.takeLast(STORY_WARM_TAIL).forEach { item ->
                    if (item is ChatListItem.Message) {
                        StoryCache.partsFor(item.row.m.id, item.row.m.contentJson)
                    }
                }
                ChatRowsSnapshot.put(whom, items)
            }
            .flowOn(Dispatchers.Default)
    }.collectAsState(initial = ChatRowsSnapshot.get(whom))

    // Unread COUNT — drives the catch-me-up banner only. Captured on
    // entry; not used for the divider anymore (see dividerAnchorId).
    var unreadSnapshot by remember(whom) { mutableStateOf<Int?>(null) }

    // "New" divider anchor: the id of the first-unread message, taken
    // from %activity's server-provided boundary (UnreadEntity.first
    // UnreadId), captured ONCE on entry. null = no divider. Anchoring
    // to the message id instead of "count from the end" fixes two
    // bugs: (1) the count includes reaction / reply events, so a
    // reaction on an old post used to drop a spurious divider over old
    // content; (2) it mis-placed the divider whenever unread events
    // outnumbered unread messages. The id boundary is exact and is
    // null precisely when the conversation is caught up.
    var dividerAnchorId by remember(whom) { mutableStateOf<String?>(null) }
    var dividerResolved by remember(whom) { mutableStateOf(false) }
    // Fade trigger. The divider element stays in the list once placed;
    // flipping this true fades it to transparent (height preserved, no
    // reflow). Never nulled back here — re-entry re-seeds the anchor.
    var dividerFaded by remember(whom) { mutableStateOf(false) }

    val displayRows = remember(rows, dividerAnchorId) {
        val anchor = dividerAnchorId
        if (anchor == null || rows.isEmpty()) rows
        else {
            val idx = rows.indexOfFirst {
                it is ChatListItem.Message && it.row.m.id == anchor
            }
            // Anchor not loaded yet (rows still paging in) → no divider
            // this pass; recomputes when `rows` updates and the message
            // lands.
            if (idx < 0) rows
            else ArrayList<ChatListItem>(rows.size + 1).apply {
                addAll(rows.subList(0, idx))
                add(ChatListItem.UnreadDivider)
                addAll(rows.subList(idx, rows.size))
            }
        }
    }

    val contactMap by remember {
        contactMapFlow(
            db.contacts().stream(),
            db.clubs().stream(),
            db.groups().streamGroups(),
            db.groups().streamChannelGroups(),
        )
    }.collectAsState(initial = ContactMap.EMPTY)

    // Current pinned-post id for this channel (chat channels only);
    // null for DMs / clubs / non-chat channels.
    val pinnedPostId by remember(whom) {
        if (whom.startsWith("chat/")) db.groups().streamPinnedPostId(whom)
        else kotlinx.coroutines.flow.flowOf(null)
    }.collectAsState(initial = null)

    val notifyPref by remember(whom) { db.notifyPrefs().stream(whom) }
        .collectAsState(initial = null)
    val notifyLevel = notifyPref?.level ?: NotifyLevel.DEFAULT

    val excludedWhoms by remember {
        db.watchwords().streamExcludes()
    }.collectAsState(initial = emptyList())
    val isExcludedFromWatchwords = remember(excludedWhoms, whom) {
        excludedWhoms.any { it.whom == whom }
    }

    val listState = rememberLazyListState()

    val isPinnedToBottom by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset == 0
        }
    }
    @Suppress("UNUSED_EXPRESSION") isPinnedToBottom

    var hasAnchored by remember(whom) { mutableStateOf(false) }
    var flashMessageId by remember(whom) { mutableStateOf<String?>(null) }
    // Tracks which anchor (if any) we've already scrolled to, so a fresh
    // initialScrollMessageId fires even when the user clicks a bookmark
    // for the chat they're already in (hasAnchored is already true from
    // the initial bottom-snap). Keyed on whom so a chat switch resets.
    var lastAppliedAnchor by remember(whom) { mutableStateOf<String?>(null) }
    LaunchedEffect(displayRows.size, initialScrollMessageId) {
        if (displayRows.isEmpty()) return@LaunchedEffect
        if (initialScrollMessageId != null && initialScrollMessageId != lastAppliedAnchor) {
            val originalIdx = displayRows.indexOfFirst { item ->
                item is ChatListItem.Message && item.row.m.id == initialScrollMessageId
            }
            if (originalIdx >= 0) {
                listState.scrollToItem(displayRows.lastIndex - originalIdx)
                hasAnchored = true
                flashMessageId = initialScrollMessageId
                lastAppliedAnchor = initialScrollMessageId
                onScrollConsumed()
                return@LaunchedEffect
            }
        }
        if (!hasAnchored) {
            listState.scrollToItem(0)
        }
        hasAnchored = true
    }
    LaunchedEffect(flashMessageId) {
        if (flashMessageId != null) {
            kotlinx.coroutines.delay(5_500)
            flashMessageId = null
        }
    }

    var forceBottomTick by remember(whom) { mutableStateOf(0) }
    // pendingSendBaselineSize: the row count captured at the moment
    // doSend fires, BEFORE the optimistic upsert lands. The
    // LaunchedEffect(rows.size) below uses it to detect "the user
    // just sent — once rows grows past this baseline, snap to bottom
    // unconditionally so their own message can't end up below the
    // fold". The baseline is set inside doSend (see the send
    // composer), not here, because the upsert is fast enough on a
    // local DB to race a LaunchedEffect-deferred snapshot.
    var pendingSendBaselineSize by remember(whom) { mutableStateOf<Int?>(null) }
    // Tracks the optimistic row's id between catch-up scroll and
    // the server-echo swap. See decideAutoScroll's swap branch.
    var pendingSelfSendNewestId by remember(whom) { mutableStateOf<String?>(null) }
    LaunchedEffect(forceBottomTick) {
        if (forceBottomTick > 0 && rows.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    // Auto-scroll-to-bottom heuristic: only fire when a NEW message
    // landed at the head (newest), not when pagination prepended
    // older messages at the tail. Without this, short chats where
    // the load-older trigger and "near bottom" guard overlap will
    // yank the user from history they were trying to read.
    // rows is oldest-first; LazyColumn renders displayRows.asReversed()
    // with reverseLayout=true so the LAST Message renders at the
    // visual bottom (newest). Walk backward to find the newest id.
    fun newestMessageId(items: List<ChatListItem>): String? {
        for (i in items.indices.reversed()) {
            val item = items[i]
            if (item is ChatListItem.Message) return item.row.m.id
        }
        return null
    }
    var lastNewestId by remember(whom) { mutableStateOf<String?>(newestMessageId(rows)) }
    var lastSize by remember(whom) { mutableStateOf(rows.size) }
    // Re-key on `(rows.size, newestMessageId(rows))` so the swap
    // emission — same size, different newest — also fires this
    // effect. rc25's "verified row jumps below fold" bug was: the
    // effect was keyed on rows.size alone, so a swap that didn't
    // change size (optimistic deleted + verified inserted in the
    // same Room transaction → one emission, size unchanged) never
    // re-ran the decision.
    val newestIdNow = newestMessageId(rows)
    LaunchedEffect(rows.size, newestIdNow) {
        val decision = io.nisfeb.talon.ui.decideAutoScroll(
            rowsSize = rows.size,
            newestId = newestIdNow,
            lastNewestId = lastNewestId,
            lastSize = lastSize,
            firstVisibleItemIndex = listState.firstVisibleItemIndex,
            pendingSendBaselineSize = pendingSendBaselineSize,
            pendingSelfSendNewestId = pendingSelfSendNewestId,
        )
        lastNewestId = newestIdNow
        lastSize = rows.size
        pendingSendBaselineSize = decision.nextBaseline
        pendingSelfSendNewestId = decision.nextPendingSelfSendNewestId
        if (decision.scrollToBottom) {
            listState.scrollToItem(0)
        }
    }

    DisposableEffect(whom) {
        onDispose { repo.setOpenChat(null) }
    }

    LaunchedEffect(whom) {
        if (!dividerResolved) {
            // Read the unread row BEFORE setOpenChat (which fires
            // markRead, clearing count + boundary). We snapshot both
            // here so the divider survives the channel-open mark-read
            // and only clears via the dwell logic below.
            val u = db.unreads().getOne(whom)
            unreadSnapshot = u?.count ?: 0
            dividerAnchorId = u?.firstUnreadId
            dividerResolved = true
        }
        repo.setOpenChat(whom)
    }

    // Dwell-fade: once the "New" divider has been continuously visible
    // for 5s (the user scrolled to it and lingered — not a fixed timer
    // from entry, which would fire before they reach it), fade it out.
    // We flip [dividerFaded] rather than removing the element, so it
    // fades in place over UNREAD_DIVIDER_FADE_MS with its height
    // preserved — nothing below reflows and no tap target slides under
    // the pointer. markRead already cleared the server + local boundary
    // on entry, so it won't reappear on re-entry until a genuinely
    // newer message arrives and %activity hands us a new firstUnreadId.
    LaunchedEffect(dividerAnchorId, whom) {
        if (dividerAnchorId == null || dividerFaded) return@LaunchedEffect
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.any {
                it.key == ChatListItem.UnreadDivider.key
            }
        }.collectLatest { visible ->
            if (visible) {
                delay(5_000)
                // Reached only if still visible after 5s — collectLatest
                // cancels this branch the moment visibility flips off.
                dividerFaded = true
            }
        }
    }

    var refreshing by remember(whom) { mutableStateOf(false) }
    LaunchedEffect(whom) {
        Log.i("DmChatScreen", "mount whom=$whom rows=${rows.size}")
        refreshing = true
        runCatching { repo.refreshConversation(whom, count = 500) }
            .onFailure { Log.w("DmChatScreen", "refresh $whom failed: ${it.message}") }
        refreshing = false
    }

    var paginating by remember(whom) { mutableStateOf(false) }
    var paginationExhausted by remember(whom) { mutableStateOf(false) }
    LaunchedEffect(whom) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        }.collect { maxIdx ->
            val total = rows.size
            if (
                total > 0 &&
                maxIdx >= total - 4 &&
                !paginating &&
                !paginationExhausted
            ) {
                paginating = true
                val hasMore = runCatching { repo.loadOlder(whom) }.getOrDefault(false)
                if (!hasMore) paginationExhausted = true
                paginating = false
            }
        }
    }

    val scope = rememberCoroutineScope()

    // ── message action sheet state ──
    var actionTarget by remember { mutableStateOf<MessageEntity?>(null) }
    // Long-press / right-click on any reaction chip surfaces the
    // per-reactor breakdown. Set to the message's reactions list and
    // the sheet renders; null = closed.
    var reactionDetailsTarget by remember(whom) {
        mutableStateOf<List<ReactionEntity>?>(null)
    }
    var editing by remember { mutableStateOf<MessageEntity?>(null) }
    var confirmingDelete by remember { mutableStateOf<MessageEntity?>(null) }

    val canSend = remember(whom) {
        whom.startsWith("~") || whom.startsWith("0v") || whom.startsWith("chat/")
    }

    val contactList by remember {
        db.contacts().stream()
    }.collectAsState(initial = emptyList())

    val allShips = remember(rows, contactList) {
        val set = linkedSetOf<String>()
        rows.forEach { item -> if (item is ChatListItem.Message) set.add(item.row.m.author) }
        contactList.forEach { set.add(it.ship) }
        set.toList()
    }

    // DM dispatch: top-level posts via repo.send / repo.sendImage,
    // quotes via repo.sendQuote.
    val dmStrategy = remember(repo, whom) {
        object : io.nisfeb.talon.ui.ChatSendStrategy {
            override suspend fun sendText(text: String) { repo.send(whom, text) }
            override suspend fun sendImage(
                src: String,
                width: Int,
                height: Int,
                alt: String,
            ) {
                repo.sendImage(
                    whom = whom,
                    src = src,
                    width = width,
                    height = height,
                    alt = alt,
                )
            }
            override val supportsQuote: Boolean = true
            override suspend fun sendQuote(
                body: String,
                quoteWhom: String,
                quoteId: String,
            ) {
                repo.sendQuote(whom, body, quoteWhom, quoteId)
            }
        }
    }

    var profileSheetShip by remember { mutableStateOf<String?>(null) }

    val currentOnOpenThread by rememberUpdatedState(onOpenThread)
    val currentOnOpenConversation by rememberUpdatedState(onOpenConversation)
    val currentOnOpenImage by rememberUpdatedState(onOpenImage)

    val onOpenThreadForMessage: (MessageEntity) -> Unit = remember {
        { m -> currentOnOpenThread(m.id) }
    }
    val onMentionTap: (String) -> Unit = remember {
        { patp -> profileSheetShip = patp }
    }
    val onAvatarTap: (String) -> Unit = remember {
        { patp -> profileSheetShip = patp }
    }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val urbLinkHandler = io.nisfeb.talon.ui.LocalUrbLinkHandler.current
    val onLinkTap: (String) -> Unit = remember(uriHandler, urbLinkHandler) {
        { url ->
            if (io.nisfeb.talon.urbit.UrbLink.isUrbUrl(url)) urbLinkHandler(url)
            else runCatching { uriHandler.openUri(url) }
        }
    }
    val onReactionForMessage: (MessageEntity, List<ReactionEntity>, String) -> Unit =
        remember(ourPatp) {
            { m, reactions, emoji ->
                // Compare on the canonical key: react() stores the
                // variation-selector-stripped form, and the picker may
                // hand us a shortcode or a glyph — normalize both sides
                // so tapping our own reaction toggles it off reliably.
                val ours = ReactionPalette.normalize(emoji)
                val mineSame = reactions.any {
                    it.author == ourPatp && ReactionPalette.normalize(it.emoji) == ours
                }
                scope.launch {
                    runCatching {
                        if (mineSame) repo.unreact(m.whom, m.id)
                        else repo.react(m.whom, m.id, emoji)
                    }.onFailure {
                        composerState.sendError = "react failed: ${it.message ?: it::class.simpleName}"
                    }
                }
                Unit
            }
        }

    Column(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                contactMap.conversationLabel(whom),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            // Group/channel chats only for v1 — clubs (`0v...`) don't
            // map to a single group flag, so GroupInfoPane's "View
            // members" handler (which resolves channel-nest →
            // group-flag via `db.groups().channelGroupFor`) silently
            // no-ops for them. DMs (`~ship`) likewise have no
            // group-info concept in v1 (architecture is chat-shape-
            // aware so club + DM support is additive later).
            val hasInfoPane = onOpenGroupInfo != null && whom.startsWith("chat/")
            if (hasInfoPane) {
                IconButton(onClick = onOpenGroupInfo) {
                    Icon(Icons.Filled.Info, contentDescription = "Info")
                }
            }
            // Hide the topic icon entirely on platforms where the
            // on-device embedder isn't supported — otherwise the
            // user gets an icon that opens a sheet stuck saying
            // "Indexer hasn't started yet" forever. The flag also
            // gates Settings rendering, so feature stays
            // discoverable on platforms that do support it.
            if (aiConfigured.smartFeaturesEnabled &&
                io.nisfeb.talon.ui.isOnDeviceAiFeatureSupported(
                    io.nisfeb.talon.ai.AiSettings.Feature.SmartFeatures,
                )
            ) {
                IconButton(onClick = { topicsSheetOpen = true }) {
                    Icon(Icons.Filled.Topic, contentDescription = "Topics in this chat")
                }
            }
            // For group channels, the Info pane already exposes the
            // notification settings — duplicating the dropdown in
            // the header is just clutter. DMs / clubs have no Info
            // pane, so the dropdown stays in the header for them.
            if (!hasInfoPane) {
                NotifyLevelDropdown(
                    level = notifyLevel,
                    enabled = repo.settingsSync != null,
                    isExcludedFromWatchwords = isExcludedFromWatchwords,
                    onSelect = { level ->
                        scope.launch {
                            runCatching { repo.settingsSync?.setNotifyLevel(whom, level) }
                                .onFailure { composerState.sendError = "notify failed: ${it.message ?: it::class.simpleName}" }
                        }
                    },
                    onToggleWatchwordExclude = {
                        scope.launch {
                            runCatching {
                                repo.settingsSync?.setWatchwordExclude(whom, !isExcludedFromWatchwords)
                            }.onFailure {
                                composerState.sendError = "watchword toggle failed: ${it.message ?: it::class.simpleName}"
                            }
                        }
                    },
                )
            }
        }
        HorizontalDivider()
        if (refreshing && rows.isEmpty()) {
            androidx.compose.material3.LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
            )
        }
        val showCatchUp = aiConfigured.hasKey() &&
            aiConfigured.catchMeUpEnabled &&
            (unreadSnapshot ?: 0) >= CATCH_UP_MIN_UNREAD &&
            catchUpSummary == null
        if (showCatchUp) {
            CatchMeUpBanner(
                count = unreadSnapshot ?: 0,
                loading = catchingUp,
                onClick = {
                    if (catchingUp) return@CatchMeUpBanner
                    catchingUp = true
                    catchUpError = null
                    scope.launch {
                        runCatching {
                            val count = (unreadSnapshot ?: 0).coerceIn(1, 60)
                            val latest = db.messages().latestFor(whom, count)
                            val ordered = latest.asReversed()
                            aiFeatures.catchMeUp(ordered) { patp ->
                                contactMap.displayName(patp)
                            }
                        }.onSuccess { catchUpSummary = it }
                            .onFailure { catchUpError = it.message ?: it::class.simpleName }
                        catchingUp = false
                    }
                },
            )
        }
        // Pinned-post banner — chat channels only, surfaces just
        // above the message list when an admin has pinned a post.
        // Subtle on purpose: surfaceVariant background, small pin
        // icon, single-line preview. Tap → scroll to the message.
        pinnedPostId?.let { pinId ->
            PinnedPostBanner(
                whom = whom,
                postId = pinId,
                db = db,
                contactMap = contactMap,
                onTap = {
                    val idx = displayRows.indexOfFirst {
                        it is ChatListItem.Message && it.row.m.id == pinId
                    }
                    if (idx >= 0) {
                        val reverseIdx = displayRows.size - 1 - idx
                        scope.launch { listState.animateScrollToItem(reverseIdx) }
                        flashMessageId = pinId
                    }
                },
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
        // Empty-state placeholder. Triggers when the refresh has
        // finished and we still have no rows — usually a
        // never-DMed peer where the ship has no writ history. We
        // keep the composer enabled below; the first send creates
        // the DM on the ship side, so "say hi" is the literal fix.
        if (!refreshing && displayRows.isEmpty()) {
            EmptyChatPlaceholder(
                label = contactMap.conversationLabel(whom),
                isDm = whom.startsWith("~"),
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
            )
        }
        val chatDensity = io.nisfeb.talon.ui.LocalChatDensity.current
        // Admin-groups cache for the pin gate. Null until the
        // bootstrap refresh in App.kt completes; we fall back to
        // "is the user the group host?" until it lands so the
        // option still appears immediately for host-admins. See
        // [io.nisfeb.talon.urbit.canPinInGroup].
        val adminGroups by repo.adminGroupsFlow.collectAsState()
        // Per-row action-menu body. Wired here so the slot's
        // closure captures repo / scope / composerState / pinned
        // state without exploding [MessageRow]'s parameter list.
        // The slot is composed only when a row's menu is expanded
        // (DropdownMenu's content composes lazily), so the
        // bookmark/pinned lookups don't run for every list row.
        val clipboardManager = LocalClipboardManager.current
        val messageActionMenuFor: @Composable (MessageEntity) -> Unit = { target ->
            val isBookmarked by remember(target.whom, target.id) {
                db.bookmarks().isBookmarked(target.whom, target.id)
            }.collectAsState(initial = false)
            val canBookmark = repo.settingsSync != null

            MessageActionMenu(
                db = db,
                message = target,
                ourPatp = ourPatp,
                isChannel = whom.startsWith("chat/"),
                isBookmarked = isBookmarked,
                isPinned = pinnedPostId == target.id,
                canBookmark = canBookmark,
                canPin = whom.startsWith("chat/") && target.parentId == null &&
                    io.nisfeb.talon.urbit.canPinInGroup(
                        ourPatp = ourPatp,
                        groupFlag = contactMap.groupOfChannel(whom),
                        adminGroups = adminGroups,
                    ),
                canQuote = whom.startsWith("chat/") && target.parentId == null,
                onDismiss = { actionTarget = null },
                onPickReaction = { emoji ->
                    actionTarget = null
                    scope.launch {
                        runCatching { repo.react(whom, target.id, emoji) }
                            .onFailure {
                                composerState.sendError =
                                    "react failed: ${it.message ?: it::class.simpleName}"
                            }
                    }
                },
                onReply = {
                    actionTarget = null
                    onOpenThread(target.id)
                },
                onQuote = {
                    actionTarget = null
                    composerState.pendingQuote = target
                },
                onCopy = {
                    actionTarget = null
                    val text = StoryCache.textFor(target.id, target.contentJson)
                    clipboardManager.setText(AnnotatedString(text))
                },
                onCopyMarkdown = {
                    actionTarget = null
                    val md = io.nisfeb.talon.urbit.RawMarkdown
                        .fromStoryJson(target.contentJson)
                    clipboardManager.setText(AnnotatedString(md))
                },
                onToggleBookmark = {
                    actionTarget = null
                    scope.launch {
                        if (isBookmarked) {
                            repo.settingsSync?.removeBookmark(target.whom, target.id)
                        } else {
                            repo.settingsSync?.addBookmark(
                                target.whom,
                                target.id,
                                nowMs(),
                            )
                        }
                    }
                },
                onEdit = {
                    actionTarget = null
                    editing = target
                },
                onDelete = {
                    actionTarget = null
                    confirmingDelete = target
                },
                onTogglePin = {
                    val wasPinned = pinnedPostId == target.id
                    actionTarget = null
                    scope.launch {
                        runCatching {
                            if (wasPinned) repo.unpinPost(whom)
                            else repo.pinPost(whom, target.id)
                        }.onFailure {
                            composerState.sendError =
                                "pin failed: ${it.message ?: it::class.simpleName}"
                        }
                    }
                },
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(chatDensity.messageSpacing),
        ) {
            items(
                items = displayRows.asReversed(),
                key = { it.key },
                contentType = { it.contentType },
            ) { item ->
                when (item) {
                    is ChatListItem.DateDivider -> DateDividerRow(item.label)
                    is ChatListItem.UnreadDivider ->
                        io.nisfeb.talon.ui.UnreadDividerRow(faded = dividerFaded)
                    is ChatListItem.Message -> {
                        val rowMsg = item.row.m
                        MessageRow(
                            row = item.row,
                            ourPatp = ourPatp,
                            contactMap = contactMap,
                            http = http,
                            menuExpanded = actionTarget?.id == rowMsg.id,
                            onMenuExpand = { actionTarget = rowMsg },
                            onMenuDismiss = { actionTarget = null },
                            actionMenu = {
                                // Per-row menu body. State lookups
                                // (bookmark flag) live in here so they
                                // only fire for the row whose menu is
                                // open. All callbacks dismiss via
                                // `actionTarget = null` to mirror the
                                // old bottom-sheet's auto-close.
                                messageActionMenuFor(rowMsg)
                            },
                            onOpenThread = onOpenThreadForMessage,
                            onReactionTap = onReactionForMessage,
                            onReactionLongPress = { reactions ->
                                reactionDetailsTarget = reactions
                            },
                            onMentionTap = onMentionTap,
                            onLinkTap = onLinkTap,
                            onImageTap = currentOnOpenImage,
                            onAvatarTap = onAvatarTap,
                            flashAmber = item.row.m.id == flashMessageId,
                        )
                    }
                }
            }
        }
        } // close empty-state Box
        TypingIndicator(whom = whom, repo = repo, contactMap = contactMap)
        io.nisfeb.talon.ui.ChatComposer(
            state = composerState,
            db = db,
            repo = repo,
            http = http,
            drafts = drafts,
            whom = whom,
            contactMap = contactMap,
            allShips = allShips,
            canSend = canSend,
            hideComposerButtons = hideComposerButtons,
            placeholder = "Message",
            locationProvider = locationProvider,
            voiceComposer = voiceComposer,
            voicePlayer = voicePlayer,
            onSlashMic = onSlashMic,
            powerFeaturesEnabled = powerFeaturesEnabled,
            // Up-arrow-on-empty-composer edits your most recent
            // message. Same predicate as the Edit menu action
            // (mine, channel chat, top-level) so it can only open
            // an edit the action menu would also allow. Null on
            // non-channel chats — %chat DMs ignore edit pokes.
            onEditLast = if (whom.startsWith("chat/")) {
                {
                    rows.asSequence()
                        .filterIsInstance<ChatListItem.Message>()
                        .map { it.row.m }
                        .filter { it.author == ourPatp && it.parentId == null }
                        .maxByOrNull { it.sentMs }
                        ?.let { editing = it }
                }
            } else null,
            onBeforeLocalEcho = {
                // Capture the row count synchronously BEFORE the
                // optimistic upsert can land, then bump the
                // force-bottom tick. The self-send-scroll
                // heuristic uses these to detect "the user just
                // sent" and snap to bottom regardless of how far
                // up they had scrolled. Setting the baseline here
                // (vs. inside a LaunchedEffect) is load-bearing —
                // see decideAutoScroll's docs and the rc23 fix.
                pendingSendBaselineSize = rows.size
                forceBottomTick += 1
            },
            strategy = dmStrategy,
        )
    }

    reactionDetailsTarget?.let { reactions ->
        io.nisfeb.talon.ui.ReactionDetailsSheet(
            reactions = reactions,
            contactMap = contactMap,
            onDismiss = { reactionDetailsTarget = null },
            onOpenProfile = { ship ->
                reactionDetailsTarget = null
                profileSheetShip = ship
            },
        )
    }

    profileSheetShip?.let { ship ->
        // Pull the live entity rather than reading from contactMap:
        // the upstream contactMap flow now suppresses status-only
        // emissions for perf, so contactMap.contact(ship)?.status
        // can be stale by minutes. The profile sheet is the one
        // surface that wants fresh status / bio.
        val freshContact by remember(ship) {
            db.contacts().streamOne(ship)
        }.collectAsState(initial = null)
        val bookContacts by repo.bookContacts.collectAsState()
        ContactProfileSheet(
            ship = ship,
            self = ship == ourPatp,
            contact = freshContact,
            isInBook = ship in bookContacts,
            onAddContact = {
                val target = ship
                profileSheetShip = null
                scope.launch { runCatching { repo.addContact(target) } }
            },
            onRemoveContact = {
                val target = ship
                profileSheetShip = null
                scope.launch { runCatching { repo.removeContact(target) } }
            },
            onMessage = {
                profileSheetShip = null
                currentOnOpenConversation(ship)
            },
            onEditSelf = {
                profileSheetShip = null
                onOpenSelfProfile()
            },
            onDismiss = { profileSheetShip = null },
        )
    }

    editing?.let { target ->
        EditMessageDialog(
            // Editable text only — a quoted post's cite (and images /
            // link previews) ride along untouched via originalContentJson.
            initial = io.nisfeb.talon.urbit.editableText(target.contentJson),
            onDismiss = { editing = null },
            onSave = { newText ->
                editing = null
                scope.launch {
                    runCatching {
                        repo.edit(
                            whom = whom,
                            postId = target.id,
                            text = newText,
                            originalSentMs = target.sentMs,
                            originalContentJson = target.contentJson,
                        )
                    }
                        .onFailure { composerState.sendError = "edit failed: ${it.message ?: it::class.simpleName}" }
                }
            },
        )
    }

    catchUpSummary?.let { summary ->
        AlertDialog(
            onDismissRequest = { catchUpSummary = null },
            title = { Text("Catch me up") },
            text = { Text(summary, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { catchUpSummary = null }) { Text("Got it") }
            },
        )
    }

    catchUpError?.let { err ->
        AlertDialog(
            onDismissRequest = { catchUpError = null },
            title = { Text("Catch me up failed") },
            text = { Text(err) },
            confirmButton = {
                TextButton(onClick = { catchUpError = null }) { Text("OK") }
            },
        )
    }

    confirmingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmingDelete = null },
            title = { Text("Delete message?") },
            text = {
                Text(
                    "This will remove the message for everyone in the chat. " +
                        "Channel admins can delete other users' messages; otherwise " +
                        "the server only allows deleting your own.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val toDelete = target
                    confirmingDelete = null
                    scope.launch {
                        runCatching { repo.delete(whom, toDelete.id, toDelete.parentId) }
                            .onFailure { composerState.sendError = "delete failed: ${it.message ?: it::class.simpleName}" }
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = null }) { Text("Cancel") }
            },
        )
    }

    if (topicsSheetOpen) {
        TopicsSheet(
            whom = whom,
            db = db,
            searchEmbedder = searchEmbedder,
            onDismiss = { topicsSheetOpen = false },
            onTapMessage = { msgId, parentId ->
                topicsSheetOpen = false
                if (parentId != null) onOpenThreadAt(parentId, msgId)
                else {
                    val idx = displayRows.indexOfFirst {
                        it is ChatListItem.Message && it.row.m.id == msgId
                    }
                    if (idx >= 0) {
                        val reverseIdx = displayRows.size - 1 - idx
                        scope.launch { listState.scrollToItem(reverseIdx) }
                        flashMessageId = msgId
                    }
                }
            },
        )
    }
}

@OptIn(
    ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
@Composable
private fun MessageRow(
    row: DisplayRow,
    ourPatp: String,
    contactMap: ContactMap,
    http: HttpClient,
    /** True when this row's action menu is open. Source of truth is
     *  the screen-level `actionTarget` so opening one menu auto-closes
     *  any other. */
    menuExpanded: Boolean,
    /** Mark this row as the one whose action menu should be open.
     *  Fires from the trailing "⋯" tap AND from desktop right-click on
     *  the row chrome. */
    onMenuExpand: () -> Unit,
    /** Clear the open-menu marker (DropdownMenu dismiss). */
    onMenuDismiss: () -> Unit,
    /** Body of the row's action menu — invoked inside a [DropdownMenu]
     *  anchored to the trailing "⋯" button. Composed only when
     *  [menuExpanded] becomes true, so the per-target state lookups
     *  (bookmark flag, pin state) don't run for every row in the list. */
    actionMenu: @Composable () -> Unit,
    onOpenThread: (MessageEntity) -> Unit,
    onReactionTap: (MessageEntity, List<ReactionEntity>, String) -> Unit,
    /** Long-press / right-click on any reaction chip — surfaces the
     *  per-reactor breakdown so the user can see who reacted with
     *  what without long-pressing the message itself. */
    onReactionLongPress: (List<ReactionEntity>) -> Unit,
    onMentionTap: (String) -> Unit,
    onLinkTap: (String) -> Unit,
    onImageTap: (String) -> Unit,
    onAvatarTap: (String) -> Unit,
    flashAmber: Boolean = false,
) {
    val m = row.m
    val parts = remember(m.id, m.contentJson) { StoryCache.partsFor(m.id, m.contentJson) }
    val stamp = remember(m.sentMs) { formatMonthDayTime(m.sentMs) }
    val authorLabel = remember(m.author, contactMap) { contactMap.displayName(m.author) }
    val avatarUrl = remember(m.author, contactMap) { contactMap.avatar(m.author) }
    val avatarColor = remember(m.author, contactMap) { contactMap.shipColor(m.author) }
    val grouped = remember(row.reactions) {
        // Normalize on read too: rows stored before we normalized on write
        // still carry FE0F, and would otherwise render as a separate chip.
        row.reactions.groupBy { ReactionPalette.normalize(it.emoji) }
            .map { (emoji, rs) -> Triple(emoji, rs.size, rs.any { it.author == ourPatp }) }
    }

    val offsetX = remember { androidx.compose.runtime.mutableStateOf(0f) }
    val isPending = remember(m.id) { m.id.startsWith("local_") }

    val flashAlpha = remember(m.id) { Animatable(0f) }
    LaunchedEffect(flashAmber) {
        if (flashAmber) {
            flashAlpha.snapTo(1f)
            flashAlpha.animateTo(0f, tween(5_000, easing = LinearEasing))
        }
    }
    val flashColor = Color(0xFFFFC107).copy(alpha = 0.30f * flashAlpha.value)

    // Desktop only: track row hover so the trailing "⋯" can reveal on
    // hover (the touch path opens the menu by tapping the row instead).
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource)
            .background(flashColor)
            // Accent tint while this message's menu is open, plus a subtle
            // hover tint (desktop) so it's clear which message the trailing
            // "⋯" acts on when rows are short or the window is wide.
            .background(
                when {
                    menuExpanded -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    hovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                    else -> Color.Transparent
                },
            )
            // Touch only: a left-tap anywhere on the row opens the action
            // menu (children that handle their own taps — avatar, reactions,
            // thread pill, images, links — consume first). On desktop this
            // clickable is OFF so a left-press-drag reaches the message
            // text's SelectionContainer instead of being swallowed and
            // popping the menu on mouse-up; desktop opens the menu via
            // right-click below. (isTapToOpenMenuSupported)
            .then(
                if (io.nisfeb.talon.ui.isTapToOpenMenuSupported) {
                    Modifier.clickable { onMenuExpand() }
                } else Modifier,
            )
            .onSecondaryClick { onMenuExpand() }
            .graphicsLayer {
                translationX = offsetX.value
                alpha = if (isPending) 0.55f else 1f
            }
            // Swipe-to-open-thread is a touch gesture only. On desktop
            // the row-level horizontal-drag detector competed with
            // child clicks — a click with a few px of horizontal drift
            // got claimed as a sub-threshold swipe and the child's
            // click (link, reaction, ⋯, thread pill) was cancelled, so
            // they read as dead. Gated off on desktop via
            // isTouchSwipeNavSupported; mouse users use the pill / ⋯ /
            // right-click instead.
            .then(
                if (io.nisfeb.talon.ui.isTouchSwipeNavSupported) {
                    Modifier.pointerInput(m.id) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                val fired = offsetX.value < -SWIPE_REPLY_THRESHOLD_PX
                                offsetX.value = 0f
                                if (fired) onOpenThread(m)
                            },
                            onDragCancel = {
                                offsetX.value = 0f
                            },
                            onHorizontalDrag = { _, dx ->
                                offsetX.value = (offsetX.value + dx)
                                    .coerceIn(-SWIPE_REPLY_MAX_PX, 0f)
                            },
                        )
                    }
                } else Modifier,
            )
            .padding(top = if (row.showHeader) 12.dp else 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (row.showHeader) {
            Avatar(
                label = authorLabel,
                url = avatarUrl,
                colorHex = avatarColor,
                size = AVATAR_SIZE,
                onClick = { onAvatarTap(m.author) },
            )
        } else {
            Spacer(Modifier.width(AVATAR_SIZE))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (row.showHeader) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "$authorLabel · $stamp",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Channel-chat send-state indicator. status is set
                    // only on our own outgoing channel posts (see
                    // TlonChatRepo.postContent / reply); DM rows leave
                    // status null and render nothing here.
                    when (m.status) {
                        "failed" -> Icon(
                            imageVector = Icons.Filled.ErrorOutline,
                            contentDescription = "Send failed",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp),
                        )
                        "pending" -> Icon(
                            imageVector = Icons.Filled.Schedule,
                            contentDescription = "Sending",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
            StoryRenderer(
                parts = parts,
                onMentionTap = onMentionTap,
                onLinkTap = onLinkTap,
                onImageTap = onImageTap,
                reactions = row.reactions,
                ourPatp = ourPatp,
                onPollVote = { emoji -> onReactionTap(m, row.reactions, emoji) },
                // Touch only: tapping the message text (off any link) opens
                // the action menu — same as tapping the row background. Null
                // on desktop so a left-press-drag in text selects rather than
                // opening the menu (right-click opens it instead).
                onMessageTap = if (io.nisfeb.talon.ui.isTapToOpenMenuSupported) onMenuExpand else null,
            )
            val firstLink = remember(parts) { firstLinkUrl(parts) }
            if (firstLink != null) {
                LinkPreviewCard(
                    url = firstLink,
                    http = http,
                    onOpen = onLinkTap,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (grouped.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    grouped.forEach { (emoji, count, mine) ->
                        ReactionChip(
                            emoji = emoji,
                            count = count,
                            mine = mine,
                            onClick = { onReactionTap(m, row.reactions, emoji) },
                            onLongClick = { onReactionLongPress(row.reactions) },
                        )
                    }
                }
            }
            if (row.replyCount > 0) {
                // Thread indicator on its own row below reactions —
                // avatar + count + relative time is wider than a
                // reaction chip and reads better as a standalone
                // affordance than inline among the emoji.
                io.nisfeb.talon.ui.ThreadIndicator(
                    count = row.replyCount,
                    lastSentMs = row.lastReplySentMs,
                    lastAuthor = row.lastReplyAuthor,
                    contactMap = contactMap,
                    nowMs = nowMs(),
                    onClick = { onOpenThread(m) },
                    hasUnread = row.threadHasUnread,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            // Action menu, anchored to the message. On touch, opened by a
            // single tap on the row (see the gated row clickable + the
            // StoryRenderer onMessageTap above); on desktop, by the trailing
            // hover "⋯" below (left-click/drag is reserved for text selection,
            // and right-click is eaten by the text's SelectionContainer).
            androidx.compose.material3.DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = onMenuDismiss,
                modifier = Modifier.widthIn(min = 280.dp, max = 360.dp),
            ) {
                actionMenu()
            }
        }
        // Desktop affordance: a hover-revealed "⋯" is the reliable way to
        // open the menu when tap-to-open is off. Without it the menu is
        // unreachable over a message's text (selection owns left-drag;
        // SelectionContainer swallows the row's right-click). A plain Icon,
        // NOT IconButton — the latter's 48dp min touch size inflates short
        // rows. Top-aligned + alpha-toggled so the slot never shifts.
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReactionChip(
    emoji: String,
    count: Int,
    mine: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val bg = if (mine)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .combinedClickableWithSecondary(
                onClick = onClick,
                onLongClick = onLongClick,
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

/**
 * Subtle pinned-post banner above the channel message list. Renders
 * `surfaceVariant` instead of the loud `secondaryContainer` an earlier
 * draft used (per user feedback "make it more subtle"); a small pin
 * icon plus a one-line preview is enough to advertise that something
 * is pinned without competing with the messages below.
 */
@Composable
private fun PinnedPostBanner(
    whom: String,
    postId: String,
    db: AppDatabase,
    contactMap: ContactMap,
    onTap: () -> Unit,
) {
    val message by remember(whom, postId) {
        db.messages().streamOne(whom, postId)
    }.collectAsState(initial = null)
    val m = message ?: return
    val preview = remember(m.id, m.contentJson) {
        StoryCache.textFor(m.id, m.contentJson).take(140).replace('\n', ' ')
    }
    val author = remember(m.author, contactMap) { contactMap.displayName(m.author) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.PushPin,
            contentDescription = "Pinned",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Text(
            "$author: $preview",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun EmptyChatPlaceholder(
    label: String,
    isDm: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "No messages yet",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = if (isDm) "Say hi to $label — your first message starts the DM."
                else "Be the first to post in this channel.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun DateDividerRow(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

// UnreadDividerRow lives in io.nisfeb.talon.ui.UnreadDivider so the
// thread list (ThreadList.kt) can reuse the exact same widget.

// Thread-safe formatter — message rendering happens on whatever
// dispatcher Compose lands on, and `buildChatListItemsReusing`
// runs on Dispatchers.Default with concurrent emissions in flight.
private val AVATAR_SIZE = 36.dp
private const val GROUP_GAP_MS = 5L * 60_000L

private const val STORY_WARM_TAIL = 30

/**
 * Minimum unread count before the catch-me-up banner appears. Below
 * this, scrolling is faster than reading a summary.
 */
private const val CATCH_UP_MIN_UNREAD = 20

private const val SWIPE_REPLY_THRESHOLD_PX = 180f
private const val SWIPE_REPLY_MAX_PX = 320f

private sealed interface ChatListItem {
    val key: String
    val contentType: String

    @androidx.compose.runtime.Immutable
    data class DateDivider(val label: String, val dayKey: String) : ChatListItem {
        override val key: String get() = "__date_$dayKey"
        override val contentType: String get() = "date"
    }

    @androidx.compose.runtime.Immutable
    data class Message(val row: DisplayRow) : ChatListItem {
        override val key: String get() = row.m.id
        override val contentType: String get() = "message"
    }

    @androidx.compose.runtime.Immutable
    data object UnreadDivider : ChatListItem {
        override val key: String get() = "__unread_line"
        override val contentType: String get() = "unread"
    }
}

@androidx.compose.runtime.Immutable
private data class DisplayRow(
    val m: MessageEntity,
    val reactions: List<ReactionEntity>,
    val replyCount: Int,
    /** Wall-clock ms of the most recent reply, or 0 when [replyCount]
     *  is 0. Drives the relative-time text on the [ThreadIndicator]. */
    val lastReplySentMs: Long,
    /** Patp of the author of the most recent reply. Empty when
     *  [replyCount] is 0; drives the indicator avatar. */
    val lastReplyAuthor: String,
    /** True when this thread has unread events (per
     *  [io.nisfeb.talon.data.ThreadUnreadEntity] for `whom + this
     *  message id`). Drives the accent tint on the [ThreadIndicator]. */
    val threadHasUnread: Boolean,
    val showHeader: Boolean,
)

private object ChatRowsSnapshot {
    private val byWhom = ConcurrentMap<String, List<ChatListItem>>()
    fun get(whom: String): List<ChatListItem> = byWhom[whom].orEmpty()
    fun put(whom: String, rows: List<ChatListItem>) { byWhom[whom] = rows }
}

private fun buildChatListItemsReusing(
    messages: List<MessageEntity>,
    reactsByPost: Map<String, List<ReactionEntity>>,
    countsByPost: Map<String, ReplyCount>,
    threadUnreadByPost: Map<String, io.nisfeb.talon.data.ThreadUnreadEntity>,
    prev: Map<String, DisplayRow>,
): Pair<List<ChatListItem>, Map<String, DisplayRow>> {
    val out = ArrayList<ChatListItem>(messages.size + 8)
    val nextMap = HashMap<String, DisplayRow>(messages.size)
    val tz = TimeZone.currentSystemDefault()
    var lastDayKey: String? = null
    var prevMsg: MessageEntity? = null
    for (m in messages) {
        val dayKey = dayKeyFor(tz, m.sentMs)
        if (dayKey != lastDayKey) {
            out.add(ChatListItem.DateDivider(label = dividerLabel(m.sentMs), dayKey = dayKey))
            lastDayKey = dayKey
            prevMsg = null
        }
        val showHeader = prevMsg == null ||
            prevMsg.author != m.author ||
            (m.sentMs - prevMsg.sentMs) > GROUP_GAP_MS
        prevMsg = m
        val reactions = reactsByPost[m.id].orEmpty()
        val digest = countsByPost[m.id]
        val replyCount = digest?.count ?: 0
        val lastReplySentMs = digest?.lastSentMs ?: 0L
        val lastReplyAuthor = digest?.lastAuthor.orEmpty()
        val threadHasUnread = (threadUnreadByPost[m.id]?.count ?: 0) > 0
        val cached = prev[m.id]
        val row = if (
            cached != null &&
            cached.m == m &&
            cached.reactions == reactions &&
            cached.replyCount == replyCount &&
            cached.lastReplySentMs == lastReplySentMs &&
            cached.lastReplyAuthor == lastReplyAuthor &&
            cached.threadHasUnread == threadHasUnread &&
            cached.showHeader == showHeader
        ) cached
        else DisplayRow(
            m = m,
            reactions = reactions,
            replyCount = replyCount,
            lastReplySentMs = lastReplySentMs,
            lastReplyAuthor = lastReplyAuthor,
            threadHasUnread = threadHasUnread,
            showHeader = showHeader,
        )
        nextMap[m.id] = row
        out.add(ChatListItem.Message(row))
    }
    return out to nextMap
}

private fun dayKeyFor(tz: TimeZone, ms: Long): String {
    val dt = Instant.fromEpochMilliseconds(ms).toLocalDateTime(tz)
    return "${dt.year}-${dt.dayOfYear}"
}

private fun dividerLabel(ms: Long): String {
    // LocalDate compares in the device zone — "Today"/"Yesterday" and
    // an abbreviated date otherwise. Runs once per date divider during
    // chat-list rebuild.
    val zone = TimeZone.currentSystemDefault()
    val today = Clock.System.now().toLocalDateTime(zone).date
    val then = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone).date
    if (then == today) return "Today"
    if (then == today.minus(1, DateTimeUnit.DAY)) return "Yesterday"
    return if (then.year == today.year) formatMonthDay(ms)
    else formatMonthDayYear(ms)
}

// ── MessageActionMenu ────────────────────────────────────────────────────────
//
// Ported from production app/src/main/java/io/nisfeb/talon/ui/screens/
// DmChatScreen.kt lines 1630–1792.
//
// Adaptations from production:
//  - `db` parameter replaces `LocalContext → TalonApplication.db` usage.
//  - `canBookmark` parameter gates the bookmark row (Option B from spec):
//    show only when repo.settingsSync != null.  Hides on desktop.
//  - `canPin` parameter gates pin/unpin.  Pin calls repo.pinPost /
//    unpinPost which live in commonMain TlonChatRepo.
//  - windowInsetsPadding(WindowInsets.navigationBars) kept — no-ops on
//    desktop but harmless.

@Composable
private fun CatchMeUpBanner(
    count: Int,
    loading: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(enabled = !loading, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("🤖", style = MaterialTheme.typography.bodyLarge)
        Text(
            if (loading) "Summarizing $count unread messages…"
            else "Catch me up on $count unread messages",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f),
        )
        if (loading) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

// IconButton + DropdownMenu for the per-conversation notify level.
// Disabled (icon stays inert) when the host hasn't supplied a
// SettingsSync — desktop falls into that path until the %settings
// bridge is wired.
@Composable
private fun NotifyLevelDropdown(
    level: String,
    enabled: Boolean,
    isExcludedFromWatchwords: Boolean,
    onSelect: (String) -> Unit,
    onToggleWatchwordExclude: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { if (enabled) open = true }, enabled = enabled) {
            Icon(
                imageVector = if (level == NotifyLevel.NONE)
                    Icons.Filled.NotificationsOff
                else Icons.Filled.Notifications,
                contentDescription = "Notifications",
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(if (level == NotifyLevel.ALL) "✓ All messages" else "All messages") },
                onClick = { open = false; onSelect(NotifyLevel.ALL) },
            )
            DropdownMenuItem(
                text = { Text(if (level == NotifyLevel.MENTIONS) "✓ Mentions only" else "Mentions only") },
                onClick = { open = false; onSelect(NotifyLevel.MENTIONS) },
            )
            DropdownMenuItem(
                text = { Text(if (level == NotifyLevel.NONE) "✓ Mute" else "Mute") },
                onClick = { open = false; onSelect(NotifyLevel.NONE) },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = {
                    Text(
                        if (isExcludedFromWatchwords) "Include in watchwords"
                        else "Exclude from watchwords"
                    )
                },
                onClick = { open = false; onToggleWatchwordExclude() },
            )
        }
    }
}

// AlertDialog with a single OutlinedTextField bound to the message
// body. Mirrors production exactly — there's no rich composer here
// because messages with images/quotes/polls aren't editable.
@Composable
private fun EditMessageDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    // TextFieldValue (not a bare String) so Shift+Enter can splice a
    // newline in at the caret. Caret starts at the end so the user can
    // immediately type or hit Return to save.
    var value by remember {
        mutableStateOf(TextFieldValue(initial, selection = TextRange(initial.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val submit = { if (value.text.isNotBlank()) onSave(value.text.trim()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit message") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .onPreviewKeyEvent { e ->
                        // Return sends the edit; Shift+Return inserts a
                        // newline (CMP Desktop won't on a hardware Return,
                        // so splice it in ourselves). Mirrors the composer.
                        if (e.type != KeyEventType.KeyDown || e.key != Key.Enter) {
                            return@onPreviewKeyEvent false
                        }
                        if (e.isShiftPressed) {
                            val sel = value.selection
                            val newText = value.text.substring(0, sel.start) +
                                "\n" + value.text.substring(sel.end)
                            value = value.copy(
                                text = newText,
                                selection = TextRange(sel.start + 1),
                            )
                        } else {
                            submit()
                        }
                        true
                    },
            )
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = value.text.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Per-message action menu rendered as the body of a [DropdownMenu]
 * anchored to the message row, opened by a single tap on the row (or
 * right-click on desktop). Previously a [ModalBottomSheet] at screen
 * level — the bottom-sheet pattern wasted vertical room and obscured
 * the message the user was acting on. Now it floats next to the
 * source row.
 *
 * Self-dismissing actions invoke [onDismiss] before firing so the menu
 * closes immediately on selection.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MessageActionMenu(
    db: AppDatabase,
    message: MessageEntity,
    ourPatp: String,
    isChannel: Boolean,
    isBookmarked: Boolean,
    isPinned: Boolean,
    canBookmark: Boolean,
    canPin: Boolean,
    onDismiss: () -> Unit,
    onPickReaction: (String) -> Unit,
    onReply: () -> Unit,
    onQuote: () -> Unit,
    canQuote: Boolean,
    onCopy: () -> Unit,
    /** Copy preserving markdown — the source the author typed. The
     *  plain [onCopy] above flattens to text for paste-into-anywhere
     *  use; this one is for forwarding / archiving / quoting where
     *  formatting matters. */
    onCopyMarkdown: () -> Unit,
    onToggleBookmark: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
) {
    val isMine = message.author == ourPatp
    val canReply = message.parentId == null
    // Compact density tokens. The previous menu used Material's
    // default 8.dp spacedBy + full TextButton heights (~48.dp each)
    // and a 16.dp-tall HorizontalDivider, which on a row with 6-8
    // actions stacked to ~400.dp of dropdown — too tall for
    // single-thumb reach on phones.
    val itemPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    val itemMinHeight = 36.dp
    Column(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
            val topUsage by remember {
                db.reactionUsage().streamTop(8)
            }.collectAsState(initial = emptyList())
            var searchOpen by remember { mutableStateOf(false) }
            var searchQuery by remember { mutableStateOf("") }
            val searchFocus = remember { FocusRequester() }
            // Pull the keyboard up the moment the user taps the
            // magnifying glass — without this, Android shows the
            // text field but leaves it inert until tapped again.
            LaunchedEffect(searchOpen) {
                if (searchOpen) searchFocus.requestFocus()
            }

            // Merge usage-ranked reactions with the default palette so
            // the row is always 8 wide even before the user has reacted
            // much. De-dupe by canonical reaction (normalize) so a glyph
            // already in history (❤️) and its palette code (:heart:) don't
            // both show as separate-looking options. Usage rows rank
            // first, then the palette fills the rest.
            val suggested = remember(topUsage) {
                val seen = mutableSetOf<String>()
                val out = mutableListOf<String>()
                (topUsage.map { it.shortcode } + ReactionPalette.picker.map { it.first })
                    .forEach { item ->
                        if (seen.add(ReactionPalette.normalize(item))) out.add(item)
                    }
                out.take(8)
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
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .focusRequester(searchFocus),
                )
                val results = remember(searchQuery) {
                    if (searchQuery.isBlank()) emptyList()
                    else EmojiCatalog.search(searchQuery, limit = 60)
                }
                if (results.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                    ) {
                        results.forEach { e ->
                            Text(
                                e.glyph,
                                fontFamily = io.nisfeb.talon.ui.EmojiFontFamily,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onPickReaction(e.shortcode) }
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            // Action rows — short, left-aligned, full-width so the
            // tap target spans the popover and reads like a menu
            // item instead of a centered button. minHeight 36 (vs
            // Material default 48) is the main driver of total
            // popover height.
            @Composable
            fun ActionRow(
                onClick: () -> Unit,
                label: String,
                color: androidx.compose.ui.graphics.Color = androidx.compose.material3.LocalContentColor.current,
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
            if (canBookmark) {
                ActionRow(
                    onClick = onToggleBookmark,
                    label = if (isBookmarked) "Remove bookmark" else "Bookmark",
                )
            }
            if (canReply) {
                ActionRow(onClick = onReply, label = "Reply in thread")
            }
            if (canQuote) {
                ActionRow(onClick = onQuote, label = "Quote")
            }
            // Edit on channel chats only — %chat (DMs + clubs) silently
            // ignores edit pokes. Thread replies have their own Edit
            // affordance in ThreadActionSheet; top-level posts get it here.
            if (isMine && isChannel && message.parentId == null) {
                ActionRow(onClick = onEdit, label = "Edit")
            }
            // Pin / Unpin — chat channels only, top-level posts only.
            if (canPin) {
                ActionRow(
                    onClick = onTogglePin,
                    label = if (isPinned) "Unpin" else "Pin",
                )
            }
            // Delete: always allowed on your own messages. On channels
            // we also show it for others' messages — the server
            // enforces admin-only deletion and rejects if the user
            // isn't authorized, leaving the row in place.
            if (isMine || isChannel) {
                ActionRow(
                    onClick = onDelete,
                    label = if (isMine) "Delete" else "Delete (admin)",
                    color = MaterialTheme.colorScheme.error,
                )
            }
    }
}

private enum class TopicWindow(val label: String, val ms: Long?) {
    Week("Week", 7L * 24 * 3600_000L),
    Month("Month", 30L * 24 * 3600_000L),
    All("All", null),
}

private data class TopicClusterRow(
    val representativeId: String,
    val representativeParentId: String?,
    val representativeText: String,
    val count: Int,
)

private data class TopicsResult(
    val clusters: List<TopicClusterRow>,
    val fellBackToAllTime: Boolean,
    /** Total embeddings indexed for this chat. Lets the empty-state
     *  copy distinguish "indexer is still working / not enough chat
     *  yet" from "index is fine, no clusters in this window". */
    val embeddingCount: Int,
)

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TopicsSheet(
    whom: String,
    db: AppDatabase,
    searchEmbedder: io.nisfeb.talon.ai.SearchEmbedderClient?,
    onDismiss: () -> Unit,
    onTapMessage: (id: String, parentId: String?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var window by remember(whom) { mutableStateOf(TopicWindow.Month) }
    var loading by remember(whom) { mutableStateOf(true) }
    var result by remember(whom) {
        mutableStateOf(TopicsResult(emptyList(), fellBackToAllTime = false, embeddingCount = 0))
    }

    // Re-cluster every time the indexer makes progress so the empty
    // state actually moves on its own — the previous "check back in
    // a moment" copy lied because the sheet only re-ran when the
    // user changed window. Now we recompute as embeddings land.
    val indexProgress by (searchEmbedder?.progress?.collectAsState()
        ?: remember { mutableStateOf(io.nisfeb.talon.ai.IndexProgress()) })

    LaunchedEffect(whom, window, indexProgress.indexed) {
        loading = true
        result = withContext(Dispatchers.Default) {
            buildTopicClusters(whom, db, window.ms)
        }
        loading = false
    }

    val clusters = result.clusters

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val headerText = when {
                result.fellBackToAllTime ->
                    "Topics in this chat (all time — recent window was too thin)"
                window == TopicWindow.All -> "Topics in this chat"
                else -> "Topics in the last ${window.label.lowercase()}"
            }
            Text(headerText, style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TopicWindow.values().forEach { w ->
                    FilterChip(
                        selected = w == window,
                        onClick = { window = w },
                        label = { Text(w.label) },
                    )
                }
            }
            when {
                loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                    Text("Clustering…", style = MaterialTheme.typography.bodyMedium)
                }
                clusters.isEmpty() -> {
                    // Empty state — surface real indexer state when
                    // embeddings are still landing. The previous
                    // "check back in a moment" copy lied: the sheet
                    // only re-ran when the user changed window, so
                    // even if the indexer made progress while the
                    // sheet was open, the message stayed there
                    // forever. Now we re-key on indexProgress.indexed
                    // (above) so each new batch retriggers
                    // buildTopicClusters, and the copy below shows
                    // the live counter / running flag.
                    if (result.embeddingCount < 6) {
                        IndexerStatusRow(
                            progress = indexProgress,
                            embeddedHere = result.embeddingCount,
                        )
                    } else {
                        Text(
                            "No distinct topics in this window — try a longer time range.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> clusters.forEach { c ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onTapMessage(c.representativeId, c.representativeParentId) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "${c.count}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                        Text(
                            c.representativeText,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IndexerStatusRow(
    progress: io.nisfeb.talon.ai.IndexProgress,
    embeddedHere: Int,
) {
    // Three states the user actually cares about:
    //  - running with a counter (indexing in motion)
    //  - finished but this chat hasn't accumulated enough yet
    //    (e.g. brand new chat, sparse history)
    //  - never started or zero progress for too long (likely
    //    embedder failure — surface a hint rather than spinning)
    val running = progress.running
    val total = progress.total
    val indexed = progress.indexed
    val pct = if (total > 0) (indexed * 100 / total).coerceIn(0, 100) else 0

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (running) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(14.dp),
                )
            }
            val headline = when {
                running && total > 0 -> "Indexing — $indexed / $total ($pct%)"
                running -> "Indexing — starting up"
                indexed == 0 && total == 0 ->
                    "Indexer hasn't started yet."
                else -> "Indexing complete — $indexed messages embedded."
            }
            Text(headline, style = MaterialTheme.typography.bodyMedium)
        }
        val sub = when {
            embeddedHere == 0 ->
                "This chat needs at least 6 indexed messages before topics can be " +
                    "built. None have been embedded for this conversation yet."
            embeddedHere < 6 ->
                "This chat has $embeddedHere indexed message" +
                    (if (embeddedHere == 1) "" else "s") +
                    "; topics need 6. Wait for indexing or send more in this chat."
            else -> ""
        }
        if (sub.isNotEmpty()) {
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private suspend fun buildTopicClusters(
    whom: String,
    db: AppDatabase,
    windowMs: Long?,
): TopicsResult {
    // Embedding count is independent of windowing — pull it once so
    // the sheet can show the right empty-state copy regardless of
    // which time window is selected.
    val embeddingCount = db.embeddings().forWhom(whom).size
    val initial = clusterTopicsWithin(whom, db, windowMs)
    if (initial.isNotEmpty() || windowMs == null) {
        return TopicsResult(initial, fellBackToAllTime = false, embeddingCount = embeddingCount)
    }
    val fallback = clusterTopicsWithin(whom, db, windowMs = null)
    return TopicsResult(
        clusters = fallback,
        fellBackToAllTime = fallback.isNotEmpty(),
        embeddingCount = embeddingCount,
    )
}

private suspend fun clusterTopicsWithin(
    whom: String,
    db: AppDatabase,
    windowMs: Long?,
): List<TopicClusterRow> {
    val embeddings = db.embeddings().forWhom(whom)
    if (embeddings.size < 6) return emptyList()
    val cutoffMs = windowMs?.let { nowMs() - it }
    data class Row(
        val embedding: io.nisfeb.talon.data.MessageEmbeddingEntity,
        val message: io.nisfeb.talon.data.MessageEntity,
        val text: String,
    )
    val rows = embeddings.mapNotNull { e ->
        val msg = db.messages().getOne(e.whom, e.id) ?: return@mapNotNull null
        if (msg.isDeleted) return@mapNotNull null
        if (cutoffMs != null && msg.sentMs < cutoffMs) return@mapNotNull null
        val text = io.nisfeb.talon.urbit.StoryCache
            .textFor(msg.id, msg.contentJson)
            .replace('\n', ' ')
            .trim()
        val wordCount = text.split(Regex("\\s+")).count { it.isNotBlank() }
        if (text.length < 20 || wordCount < 4) return@mapNotNull null
        Row(e, msg, text)
    }
    if (rows.size < 6) return emptyList()

    val vectors = rows.map { unpackEmbedding(it.embedding.vector, it.embedding.dim) }
    val k = (rows.size / 8).coerceIn(3, 6)
    val assignment = kMeansAssign(vectors, k)

    val out = mutableListOf<TopicClusterRow>()
    for (c in 0 until k) {
        val members = rows.indices.filter { assignment[it] == c }
        if (members.size < 2) continue
        val longest = members.maxByOrNull { rows[it].text.length } ?: continue
        val pick = rows[longest]
        out += TopicClusterRow(
            representativeId = pick.message.id,
            representativeParentId = pick.message.parentId,
            representativeText = pick.text.take(160),
            count = members.size,
        )
    }
    return out.sortedByDescending { it.count }
}

/**
 * "~bob is typing…" / "~bob is uploading an image" — %presence, folded
 * into the space just above the composer. Renders nothing at all when
 * nobody's active, when the conversation has no presence context
 * (clubs), or when the ship is older than Tlon v11.4.0 and doesn't run
 * the agent: in every one of those cases the flow is simply empty.
 */
@Composable
private fun TypingIndicator(
    whom: String,
    repo: TlonChatRepo,
    contactMap: ContactMap,
) {
    val active by remember(whom, repo) { repo.presenceIn(whom) }
        .collectAsState(initial = emptyMap())
    if (active.isEmpty()) return

    // `active` maps ship → what they're doing ("typing…", "recording
    // audio"). One person: name their action verbatim. Several: only
    // typing has a clean plural, so fall back to a count otherwise.
    val label = if (active.size == 1) {
        val (ship, verb) = active.entries.first()
        "${contactMap.displayName(ship)} is $verb"
    } else {
        val names = active.keys.sorted().map { contactMap.displayName(it) }
        if (active.values.all { it == "typing…" }) {
            if (names.size == 2) "${names[0]} and ${names[1]} are typing…"
            else "${names.size} people are typing…"
        } else {
            "${names.size} people are active"
        }
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
    )
}
