package io.nisfeb.talon.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import io.nisfeb.talon.ui.combinedClickableWithSecondary
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Topic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.CompositionLocalProvider
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
import io.nisfeb.talon.ui.CiteResolver
import io.nisfeb.talon.ui.CommandResult
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.ContactProfileSheet
import io.nisfeb.talon.ui.DraftStore
import io.nisfeb.talon.ui.EmojiCatalog
import io.nisfeb.talon.ui.EmojiPickerDropdown
import io.nisfeb.talon.ui.LinkPreviewCard
import io.nisfeb.talon.ui.LocalCiteResolver
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
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(
    ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
@Composable
fun DmChatScreen(
    db: AppDatabase,
    repo: TlonChatRepo,
    drafts: DraftStore,
    http: OkHttpClient,
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
     *  surface degrades gracefully (no chips, no voice button, no GPS
     *  for /loc, no voice preview playback). The platform entry point
     *  that creates App() supplies these — Main.kt stays null, the
     *  future MainActivity will pass real Composables. */
    entityChips: (@Composable (text: String, modifier: Modifier) -> Unit)? = null,
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
    modifier: Modifier = Modifier,
) {
    val aiConfigured by aiSettings.state.collectAsState()
    val hideComposerButtons by uiSettings.hideComposerButtons.collectAsState()
    val aiFeatures = remember(aiSettings) {
        AiFeatures(AiClient { aiSettings.state.value })
    }
    var catchUpSummary by remember(whom) { mutableStateOf<String?>(null) }
    var catchingUp by remember(whom) { mutableStateOf(false) }
    var catchUpError by remember(whom) { mutableStateOf<String?>(null) }
    var aiEmojiWorking by remember { mutableStateOf(false) }
    var topicsSheetOpen by remember(whom) { mutableStateOf(false) }
    var pendingVoice by remember(whom) { mutableStateOf<PendingVoice?>(null) }
    DisposableEffect(whom) {
        onDispose {
            pendingVoice?.let { java.io.File(it.path).delete() }
            pendingVoice = null
        }
    }
    val rows by remember(whom) {
        var prevByMsgId: Map<String, DisplayRow> = emptyMap()
        kotlinx.coroutines.flow.combine(
            db.messages().stream(whom).distinctUntilChanged(),
            db.reactions().stream(whom).distinctUntilChanged()
                .onStart { emit(emptyList()) },
            db.messages().streamReplyCounts(whom).distinctUntilChanged()
                .onStart { emit(emptyList()) },
        ) { messages, reactions, replyCounts ->
            if (messages.isEmpty()) {
                prevByMsgId = emptyMap()
                emptyList()
            } else {
                val (items, nextMap) = buildChatListItemsReusing(
                    messages = messages,
                    reactsByPost = reactions.groupBy { it.postId },
                    countsByPost = replyCounts.associateBy(ReplyCount::postId),
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

    var unreadSnapshot by remember(whom) { mutableStateOf<Int?>(null) }

    val displayRows = remember(rows, unreadSnapshot) {
        val n = unreadSnapshot ?: 0
        if (n <= 0 || rows.isEmpty()) rows
        else {
            var remaining = n
            var insertAt = rows.size
            for (i in rows.indices.reversed()) {
                if (rows[i] is ChatListItem.Message) {
                    if (remaining == 0) break
                    remaining--
                }
                insertAt = i
                if (remaining == 0) break
            }
            if (insertAt >= rows.size) rows
            else {
                ArrayList<ChatListItem>(rows.size + 1).apply {
                    addAll(rows.subList(0, insertAt))
                    add(ChatListItem.UnreadDivider)
                    addAll(rows.subList(insertAt, rows.size))
                }
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
    LaunchedEffect(displayRows.size, initialScrollMessageId) {
        if (displayRows.isEmpty()) return@LaunchedEffect
        if (!hasAnchored && initialScrollMessageId != null) {
            val originalIdx = displayRows.indexOfFirst { item ->
                item is ChatListItem.Message && item.row.m.id == initialScrollMessageId
            }
            if (originalIdx >= 0) {
                listState.scrollToItem(displayRows.lastIndex - originalIdx)
                hasAnchored = true
                flashMessageId = initialScrollMessageId
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
    LaunchedEffect(rows.size) {
        val newestId = newestMessageId(rows)
        val gotNewerHead =
            newestId != null && newestId != lastNewestId && rows.size > lastSize
        lastNewestId = newestId
        lastSize = rows.size
        if (gotNewerHead && listState.firstVisibleItemIndex <= 12) {
            listState.scrollToItem(0)
        }
    }

    DisposableEffect(whom) {
        onDispose { repo.setOpenChat(null) }
    }

    LaunchedEffect(whom) {
        if (unreadSnapshot == null) {
            unreadSnapshot = db.unreads().getOne(whom)?.count ?: 0
        }
        repo.setOpenChat(whom)
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
    var draft by remember(whom) { mutableStateOf(TextFieldValue(drafts.load(whom))) }
    LaunchedEffect(whom, draft.text) {
        drafts.save(whom, draft.text)
    }
    var sendError by remember(whom) { mutableStateOf<String?>(null) }
    var uploading by remember { mutableStateOf(false) }

    val pickImage = rememberImagePicker()
    val pickAnyFile = io.nisfeb.talon.util.rememberAnyFilePicker()
    val onPickAndSendImage: () -> Unit = {
        scope.launch {
            val picked = pickImage() ?: return@launch
            uploading = true
            sendError = null
            runCatching {
                val dims = decodeImageDimensions(picked.bytes)
                val hostedUrl = repo.uploadImage(picked.bytes, picked.mimeType, picked.displayName)
                repo.sendImage(
                    whom = whom,
                    src = hostedUrl,
                    width = dims?.first ?: 0,
                    height = dims?.second ?: 0,
                    alt = picked.displayName,
                )
            }.onFailure { err ->
                sendError = "image failed: ${err.message ?: err::class.simpleName}"
            }
            uploading = false
        }
    }
    // Non-image file attach. Uploads via the same memex/storage path
    // used by images and posts the resulting URL as a plain message —
    // Tlon clients render a bare URL as a link preview, so the
    // recipient gets a clickable card. Keeps us out of needing a
    // dedicated file-message JSON shape.
    val onPickAndSendFile: () -> Unit = {
        scope.launch {
            val picked = pickAnyFile() ?: return@launch
            uploading = true
            sendError = null
            runCatching {
                val hostedUrl = repo.uploadImage(picked.bytes, picked.mimeType, picked.displayName)
                repo.send(whom, hostedUrl)
            }.onFailure { err ->
                sendError = "file failed: ${err.message ?: err::class.simpleName}"
            }
            uploading = false
        }
    }

    // ── message action sheet state ──
    var actionTarget by remember { mutableStateOf<MessageEntity?>(null) }
    var editing by remember { mutableStateOf<MessageEntity?>(null) }
    var confirmingDelete by remember { mutableStateOf<MessageEntity?>(null) }
    var pendingQuote by remember(whom) { mutableStateOf<MessageEntity?>(null) }

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
    val mention = detectMentionQuery(draft.text, draft.selection.start)
    val suggestions = remember(mention, allShips, contactMap) {
        mention?.let { (q, _) -> suggestionsFor(q, contactMap, allShips) } ?: emptyList()
    }
    val emojiQuery = detectEmojiQuery(draft.text, draft.selection.start)
    val emojiSuggestions = remember(emojiQuery) {
        emojiQuery?.let { (q, _) -> EmojiCatalog.search(q, limit = 6) } ?: emptyList()
    }
    val slashTrigger = detectSlashTrigger(draft.text, draft.selection.start)
    val slashSuggestions = remember(slashTrigger) {
        slashTrigger?.let { filterSlashCommands(it.query) } ?: emptyList()
    }

    var profileSheetShip by remember { mutableStateOf<String?>(null) }

    val citeResolver = remember(db, repo) {
        object : CiteResolver {
            override suspend fun findLocal(whom: String, da: String): MessageEntity? =
                db.messages().findByDa(whom, da)
            override suspend fun fetchPost(whom: String, da: String): MessageEntity? =
                repo.fetchCitePost(whom, da)
            override suspend fun fetchReply(
                whom: String,
                postDa: String,
                replyDa: String,
            ): MessageEntity? = repo.fetchCiteReply(whom, postDa, replyDa)
        }
    }

    val currentOnOpenThread by rememberUpdatedState(onOpenThread)
    val currentOnOpenConversation by rememberUpdatedState(onOpenConversation)
    val currentOnOpenImage by rememberUpdatedState(onOpenImage)

    val onLongPressMessage: (MessageEntity) -> Unit = { m -> actionTarget = m }
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
    val onLinkTap: (String) -> Unit = remember(uriHandler) {
        { url -> runCatching { uriHandler.openUri(url) } }
    }
    val onCitationTap: (String) -> Unit = remember {
        { target -> currentOnOpenConversation(target) }
    }
    val onReactionForMessage: (MessageEntity, List<ReactionEntity>, String) -> Unit =
        remember(ourPatp) {
            { m, reactions, emoji ->
                val mineSame = reactions.any { it.author == ourPatp && it.emoji == emoji }
                scope.launch {
                    runCatching {
                        if (mineSame) repo.unreact(m.whom, m.id)
                        else repo.react(m.whom, m.id, emoji)
                    }.onFailure {
                        sendError = "react failed: ${it.message ?: it::class.simpleName}"
                    }
                }
                Unit
            }
        }

    CompositionLocalProvider(LocalCiteResolver provides citeResolver) {
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
                if (aiConfigured.topicClustersEnabled) {
                    IconButton(onClick = { topicsSheetOpen = true }) {
                        Icon(Icons.Filled.Topic, contentDescription = "Topics in this chat")
                    }
                }
                NotifyLevelDropdown(
                    level = notifyLevel,
                    enabled = repo.settingsSync != null,
                    isExcludedFromWatchwords = isExcludedFromWatchwords,
                    onSelect = { level ->
                        scope.launch {
                            runCatching { repo.settingsSync?.setNotifyLevel(whom, level) }
                                .onFailure { sendError = "notify failed: ${it.message ?: it::class.simpleName}" }
                        }
                    },
                    onToggleWatchwordExclude = {
                        scope.launch {
                            runCatching {
                                repo.settingsSync?.setWatchwordExclude(whom, !isExcludedFromWatchwords)
                            }.onFailure {
                                sendError = "watchword toggle failed: ${it.message ?: it::class.simpleName}"
                            }
                        }
                    },
                )
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
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                reverseLayout = true,
            ) {
                items(
                    items = displayRows.asReversed(),
                    key = { it.key },
                    contentType = { it.contentType },
                ) { item ->
                    when (item) {
                        is ChatListItem.DateDivider -> DateDividerRow(item.label)
                        is ChatListItem.UnreadDivider -> UnreadDividerRow()
                        is ChatListItem.Message -> MessageRow(
                            row = item.row,
                            ourPatp = ourPatp,
                            contactMap = contactMap,
                            http = http,
                            onLongPress = onLongPressMessage,
                            onOpenThread = onOpenThreadForMessage,
                            onReactionTap = onReactionForMessage,
                            onMentionTap = onMentionTap,
                            onLinkTap = onLinkTap,
                            onImageTap = currentOnOpenImage,
                            onAvatarTap = onAvatarTap,
                            onCitationTap = onCitationTap,
                            entityActionsEnabled = aiConfigured.entityActionsEnabled,
                            entityChips = entityChips,
                            flashAmber = item.row.m.id == flashMessageId,
                        )
                    }
                }
            }
            if (sendError != null) {
                Text(
                    sendError!!,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (slashSuggestions.isNotEmpty() && slashTrigger != null) {
                SlashPicker(
                    suggestions = slashSuggestions,
                    onPick = { spec ->
                        val newText = "/${spec.name} "
                        draft = TextFieldValue(
                            text = newText,
                            selection = TextRange(newText.length),
                        )
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
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
            if (suggestions.isNotEmpty() && mention != null) {
                MentionPicker(
                    suggestions = suggestions,
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
            val doSend: () -> Boolean = {
                val body = draft.text.trim()
                val quote = pendingQuote
                // Allow a bare quote with no body so users can react to a
                // message by quoting it without adding text.
                val canEmit = (body.isNotEmpty() || quote != null) && canSend
                if (!canEmit) {
                    false
                } else {
                    // Intercept the UI-dispatched commands (pickers /
                    // recorder) before runCommand. The autocomplete
                    // surfaces /img /file /mic; without this branch
                    // they'd be silently swallowed by runCommand's
                    // `Handled` fallthrough. Quoted sends bypass —
                    // a quote is a structured payload, not text the
                    // command runner is meant to interpret.
                    val firstWord = body.lowercase().substringBefore(' ')
                    val handledInUi = when {
                        quote != null -> false
                        firstWord == "/img" -> {
                            onPickAndSendImage()
                            true
                        }
                        firstWord == "/file" -> {
                            onPickAndSendFile()
                            true
                        }
                        firstWord == "/mic" -> {
                            if (onSlashMic != null) {
                                onSlashMic()
                            } else {
                                sendError =
                                    "/mic: tap the mic button instead — slash trigger isn't wired here"
                            }
                            true
                        }
                        else -> false
                    }
                    draft = TextFieldValue("")
                    drafts.clear(whom)
                    sendError = null
                    forceBottomTick += 1
                    pendingQuote = null
                    if (!handledInUi) scope.launch {
                        runCatching {
                            val cmd = if (quote == null) {
                                runCommand(
                                    rawText = body,
                                    repo = repo,
                                    http = http,
                                    locationProvider = locationProvider,
                                    toast = { msg -> sendError = msg },
                                )
                            } else CommandResult.NotACommand
                            when (cmd) {
                                is CommandResult.Send -> repo.send(whom, cmd.body)
                                is CommandResult.Handled -> {}
                                is CommandResult.Error -> sendError = cmd.message
                                is CommandResult.NotACommand -> {
                                    if (quote != null) {
                                        repo.sendQuote(whom, body, quote.whom, quote.id)
                                    } else {
                                        repo.send(whom, body)
                                    }
                                }
                            }
                        }.onFailure { err ->
                            Log.e("DmChatScreen", "send failed", err)
                            sendError = "send failed: ${err.message ?: err::class.simpleName}"
                        }
                    }
                    true
                }
            }
            pendingQuote?.let { q ->
                QuotePreviewRow(
                    target = q,
                    contactMap = contactMap,
                    onDismiss = { pendingQuote = null },
                )
            }
            val pv = pendingVoice
            if (pv != null) {
                VoicePreviewRow(
                    pending = pv,
                    sending = uploading,
                    voicePlayer = voicePlayer,
                    onCancel = {
                        java.io.File(pv.path).delete()
                        pendingVoice = null
                    },
                    onSend = {
                        uploading = true
                        sendError = null
                        pendingVoice = null
                        scope.launch {
                            runCatching {
                                val file = java.io.File(pv.path)
                                val bytes = file.readBytes()
                                val hostedUrl = repo.uploadImage(
                                    bytes = bytes,
                                    contentType = "audio/mp4",
                                    fileName = file.name,
                                )
                                val seconds = (pv.durationMs / 1000L).coerceAtLeast(1L)
                                val label = "🎙 Voice ${seconds}s"
                                repo.send(whom, "[$label]($hostedUrl)")
                                file.delete()
                            }.onFailure { err ->
                                Log.e("DmChatScreen", "voice send failed", err)
                                sendError = "voice send failed: ${err.message ?: err::class.simpleName}"
                            }
                            uploading = false
                        }
                    },
                )
                return@Column
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (!hideComposerButtons) {
                    IconButton(
                        onClick = onPickAndSendImage,
                        enabled = canSend && !uploading,
                        modifier = Modifier.size(36.dp),
                    ) {
                        if (uploading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Filled.Image,
                                contentDescription = "Attach image",
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    IconButton(
                        onClick = onPickAndSendFile,
                        enabled = canSend && !uploading,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Filled.AttachFile,
                            contentDescription = "Attach file",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    if (voiceComposer != null) {
                        voiceComposer(
                            canSend && !uploading,
                            { path, durationMs ->
                                pendingVoice = PendingVoice(path, durationMs)
                            },
                        )
                    }
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("Message") },
                    enabled = canSend,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .weight(1f)
                        .onPreviewKeyEvent { e ->
                            if (
                                e.type == KeyEventType.KeyDown &&
                                e.key == Key.Enter &&
                                !e.isShiftPressed
                            ) {
                                doSend()
                                true
                            } else false
                        },
                )
                IconButton(
                    onClick = { doSend() },
                    enabled = canSend && draft.text.isNotBlank(),
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        profileSheetShip?.let { ship ->
            ContactProfileSheet(
                ship = ship,
                self = ship == ourPatp,
                contact = contactMap.contact(ship),
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

        actionTarget?.let { target ->
            val isBookmarked by remember(target.whom, target.id) {
                db.bookmarks().isBookmarked(target.whom, target.id)
            }.collectAsState(initial = false)

            val clipboardManager = LocalClipboardManager.current
            val canBookmark = repo.settingsSync != null

            MessageActionSheet(
                db = db,
                message = target,
                ourPatp = ourPatp,
                isChannel = whom.startsWith("chat/"),
                isBookmarked = isBookmarked,
                isPinned = pinnedPostId == target.id,
                canBookmark = canBookmark,
                onDismiss = { actionTarget = null },
                onPickReaction = { emoji ->
                    actionTarget = null
                    scope.launch {
                        runCatching { repo.react(whom, target.id, emoji) }
                            .onFailure { sendError = "react failed: ${it.message ?: it::class.simpleName}" }
                    }
                },
                onReply = {
                    actionTarget = null
                    onOpenThread(target.id)
                },
                onQuote = {
                    actionTarget = null
                    pendingQuote = target
                },
                canQuote = whom.startsWith("chat/") && target.parentId == null,
                onCopy = {
                    actionTarget = null
                    val text = StoryCache.textFor(target.id, target.contentJson)
                    clipboardManager.setText(AnnotatedString(text))
                },
                onToggleBookmark = {
                    actionTarget = null
                    // Only reached when canBookmark == true; settingsSync is non-null.
                    scope.launch {
                        if (isBookmarked) {
                            repo.settingsSync?.removeBookmark(target.whom, target.id)
                        } else {
                            repo.settingsSync?.addBookmark(
                                target.whom,
                                target.id,
                                System.currentTimeMillis(),
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
                            sendError = "pin failed: ${it.message ?: it::class.simpleName}"
                        }
                    }
                },
                canPin = whom.startsWith("chat/") && target.parentId == null,
                showAiEmoji = aiConfigured.hasKey() && aiConfigured.emojiReactEnabled,
                aiEmojiWorking = aiEmojiWorking,
                onAiEmoji = {
                    if (aiEmojiWorking) return@MessageActionSheet
                    aiEmojiWorking = true
                    scope.launch {
                        runCatching {
                            val text = StoryCache.textFor(target.id, target.contentJson)
                            aiFeatures.suggestEmojiReact(text)
                        }.onSuccess { code ->
                            if (code != null) {
                                runCatching { repo.react(whom, target.id, code) }
                                    .onFailure {
                                        sendError = "react failed: ${it.message ?: it::class.simpleName}"
                                    }
                            } else {
                                sendError = "AI didn't return a known reaction"
                            }
                        }.onFailure {
                            sendError = "AI react failed: ${it.message ?: it::class.simpleName}"
                        }
                        aiEmojiWorking = false
                        actionTarget = null
                    }
                },
            )
        }
    }

    editing?.let { target ->
        EditMessageDialog(
            initial = StoryCache.textFor(target.id, target.contentJson),
            onDismiss = { editing = null },
            onSave = { newText ->
                editing = null
                scope.launch {
                    runCatching { repo.edit(whom, target.id, newText, target.sentMs) }
                        .onFailure { sendError = "edit failed: ${it.message ?: it::class.simpleName}" }
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
                            .onFailure { sendError = "delete failed: ${it.message ?: it::class.simpleName}" }
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
    http: OkHttpClient,
    onLongPress: (MessageEntity) -> Unit,
    onOpenThread: (MessageEntity) -> Unit,
    onReactionTap: (MessageEntity, List<ReactionEntity>, String) -> Unit,
    onMentionTap: (String) -> Unit,
    onLinkTap: (String) -> Unit,
    onImageTap: (String) -> Unit,
    onAvatarTap: (String) -> Unit,
    onCitationTap: (String) -> Unit,
    entityActionsEnabled: Boolean,
    entityChips: (@Composable (text: String, modifier: Modifier) -> Unit)? = null,
    flashAmber: Boolean = false,
) {
    val m = row.m
    val parts = remember(m.id, m.contentJson) { StoryCache.partsFor(m.id, m.contentJson) }
    val stamp = remember(m.sentMs) { TIME_FORMAT.format(Date(m.sentMs)) }
    val authorLabel = remember(m.author, contactMap) { contactMap.displayName(m.author) }
    val avatarUrl = remember(m.author, contactMap) { contactMap.avatar(m.author) }
    val avatarColor = remember(m.author, contactMap) { contactMap.shipColor(m.author) }
    val grouped = remember(row.reactions) {
        row.reactions.groupBy { it.emoji }
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(flashColor)
            .combinedClickableWithSecondary(onClick = {}, onLongClick = { onLongPress(m) })
            .graphicsLayer {
                translationX = offsetX.value
                alpha = if (isPending) 0.55f else 1f
            }
            .pointerInput(m.id) {
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
                Text(
                    "$authorLabel · $stamp",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StoryRenderer(
                parts = parts,
                onMentionTap = onMentionTap,
                onLinkTap = onLinkTap,
                onImageTap = onImageTap,
                onCitationTap = onCitationTap,
                onLongPress = { onLongPress(m) },
                reactions = row.reactions,
                ourPatp = ourPatp,
                onPollVote = { emoji -> onReactionTap(m, row.reactions, emoji) },
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
            // ML Kit entity extraction is Android-only; the desktop
            // actual is a no-op so this composable is safe to call from
            // commonMain. Toggle gated on the user's AI settings.
            if (entityActionsEnabled) {
                val plainText = remember(m.id, m.contentJson) {
                    StoryCache.textFor(m.id, m.contentJson)
                }
                entityChips?.invoke(plainText, Modifier.padding(top = 4.dp))
            }
            if (grouped.isNotEmpty() || row.replyCount > 0) {
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
                        )
                    }
                    if (row.replyCount > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { onOpenThread(m) }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                "💬 ${row.replyCount}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
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
            .clickable(onClick = onClick)
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

@Composable
private fun UnreadDividerRow() {
    val amber = Color(0xFFFFB74D)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = amber)
        Text(
            "New",
            style = MaterialTheme.typography.labelSmall,
            color = amber,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = amber)
    }
}

private val TIME_FORMAT = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())

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
    val showHeader: Boolean,
)

private object ChatRowsSnapshot {
    private val byWhom = java.util.concurrent.ConcurrentHashMap<String, List<ChatListItem>>()
    fun get(whom: String): List<ChatListItem> = byWhom[whom].orEmpty()
    fun put(whom: String, rows: List<ChatListItem>) { byWhom[whom] = rows }
}

private fun buildChatListItemsReusing(
    messages: List<MessageEntity>,
    reactsByPost: Map<String, List<ReactionEntity>>,
    countsByPost: Map<String, ReplyCount>,
    prev: Map<String, DisplayRow>,
): Pair<List<ChatListItem>, Map<String, DisplayRow>> {
    val out = ArrayList<ChatListItem>(messages.size + 8)
    val nextMap = HashMap<String, DisplayRow>(messages.size)
    val cal = java.util.Calendar.getInstance()
    var lastDayKey: String? = null
    var prevMsg: MessageEntity? = null
    for (m in messages) {
        val dayKey = dayKeyFor(cal, m.sentMs)
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
        val replyCount = countsByPost[m.id]?.count ?: 0
        val cached = prev[m.id]
        val row = if (
            cached != null &&
            cached.m == m &&
            cached.reactions == reactions &&
            cached.replyCount == replyCount &&
            cached.showHeader == showHeader
        ) cached
        else DisplayRow(m = m, reactions = reactions, replyCount = replyCount, showHeader = showHeader)
        nextMap[m.id] = row
        out.add(ChatListItem.Message(row))
    }
    return out to nextMap
}

private fun dayKeyFor(cal: java.util.Calendar, ms: Long): String {
    cal.timeInMillis = ms
    val y = cal.get(java.util.Calendar.YEAR)
    val d = cal.get(java.util.Calendar.DAY_OF_YEAR)
    return "$y-$d"
}

private val DIVIDER_DATE_FMT = SimpleDateFormat("MMM d", Locale.getDefault())
private val DIVIDER_DATE_FMT_OLD = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

private fun dividerLabel(ms: Long): String {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    val sameYear = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR)
    val sameDay = sameYear &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    if (sameDay) return "Today"
    val yesterday = java.util.Calendar.getInstance().apply {
        add(java.util.Calendar.DAY_OF_YEAR, -1)
    }
    val wasYesterday = yesterday.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        yesterday.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    if (wasYesterday) return "Yesterday"
    return if (sameYear) DIVIDER_DATE_FMT.format(Date(ms))
    else DIVIDER_DATE_FMT_OLD.format(Date(ms))
}

// ── MessageActionSheet ────────────────────────────────────────────────────────
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

/** Recorded-but-unsent voice clip, waiting on user review. */
private data class PendingVoice(val path: String, val durationMs: Long)

// Minimal review row that replaces the composer while a recording
// is staged. Diverges from production: production uses ExoPlayer
// for inline playback; commonMain skips that step and only offers
// Cancel / Send. A future Stage F follow-up can layer in a
// platform-actual audio playback shim if users want it back.
@Composable
private fun QuotePreviewRow(
    target: MessageEntity,
    contactMap: ContactMap,
    onDismiss: () -> Unit,
) {
    val author = remember(target.author, contactMap) { contactMap.displayName(target.author) }
    val preview = remember(target.id, target.contentJson) {
        StoryCache.textFor(target.id, target.contentJson)
            .replace('\n', ' ')
            .take(160)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Quoting $author",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                preview.ifBlank { "(attachment)" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Cancel quote",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun VoicePreviewRow(
    pending: PendingVoice,
    sending: Boolean,
    voicePlayer: (@Composable (path: String, sending: Boolean) -> Unit)?,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    val seconds = (pending.durationMs / 1000L).coerceAtLeast(1L)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Platform-supplied play/pause control. When null (desktop),
        // the row falls back to a "preview not available" hint.
        if (voicePlayer != null) {
            voicePlayer(pending.path, sending)
        }
        val label = if (voicePlayer != null) "🎙 ${seconds}s"
        else "🎙 ${seconds}s recorded — preview not available, tap send when ready"
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        IconButton(
            onClick = onCancel,
            enabled = !sending,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Discard recording",
                modifier = Modifier.size(22.dp),
            )
        }
        IconButton(
            onClick = onSend,
            enabled = !sending,
            modifier = Modifier.size(36.dp),
        ) {
            if (sending) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send recording",
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

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

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MessageActionSheet(
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
    onToggleBookmark: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
    showAiEmoji: Boolean,
    aiEmojiWorking: Boolean,
    onAiEmoji: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val isMine = message.author == ourPatp
    val canReply = message.parentId == null
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val topUsage by remember {
                db.reactionUsage().streamTop(8)
            }.collectAsState(initial = emptyList())
            var searchOpen by remember { mutableStateOf(false) }
            var searchQuery by remember { mutableStateOf("") }

            // Merge usage-ranked codes with the default palette so the
            // row is always 8 wide even before the user has reacted much.
            val suggested = remember(topUsage) {
                val used = topUsage.map { it.shortcode }
                val fallback = ReactionPalette.picker.map { it.first }
                (used + fallback.filter { it !in used }).take(8)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "React",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    searchOpen = !searchOpen
                    if (!searchOpen) searchQuery = ""
                }) {
                    Icon(Icons.Filled.Search, contentDescription = "Search emojis")
                }
                if (showAiEmoji) {
                    TextButton(onClick = onAiEmoji, enabled = !aiEmojiWorking) {
                        if (aiEmojiWorking) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text("🤖 AI pick")
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                suggested.forEach { code ->
                    val glyph = ReactionPalette.display(code)
                    Text(
                        glyph,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onPickReaction(code) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
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
                        .padding(top = 4.dp),
                )
                val results = remember(searchQuery) {
                    if (searchQuery.isBlank()) emptyList()
                    else EmojiCatalog.search(searchQuery, limit = 60)
                }
                if (results.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        results.forEach { e ->
                            Text(
                                e.glyph,
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onPickReaction(e.shortcode) }
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            TextButton(onClick = onCopy) { Text("Copy text") }
            if (canBookmark) {
                TextButton(onClick = onToggleBookmark) {
                    Text(if (isBookmarked) "Remove bookmark" else "Bookmark")
                }
            }
            if (canReply) {
                TextButton(onClick = onReply) { Text("Reply in thread") }
            }
            if (canQuote) {
                TextButton(onClick = onQuote) { Text("Quote") }
            }
            // Edit is only supported on top-level channel messages that
            // the user authored. %chat (DMs + clubs) silently ignores
            // edit pokes, and reply-edit isn't in either agent's mold.
            if (isMine && isChannel && message.parentId == null) {
                TextButton(onClick = onEdit) { Text("Edit") }
            }
            // Pin / Unpin — chat channels only, top-level posts only.
            if (canPin) {
                TextButton(onClick = onTogglePin) {
                    Text(if (isPinned) "Unpin" else "Pin (admin)")
                }
            }
            // Delete: always allowed on your own messages. On channels
            // we also show it for others' messages — the server
            // enforces admin-only deletion and rejects if the user
            // isn't authorized, leaving the row in place.
            if (isMine || isChannel) {
                TextButton(onClick = onDelete) {
                    Text(
                        if (isMine) "Delete" else "Delete (admin)",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
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
)

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TopicsSheet(
    whom: String,
    db: AppDatabase,
    onDismiss: () -> Unit,
    onTapMessage: (id: String, parentId: String?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var window by remember(whom) { mutableStateOf(TopicWindow.Month) }
    var loading by remember(whom) { mutableStateOf(true) }
    var result by remember(whom) {
        mutableStateOf(TopicsResult(emptyList(), fellBackToAllTime = false))
    }

    LaunchedEffect(whom, window) {
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
                clusters.isEmpty() -> Text(
                    "Not enough messages indexed for this chat yet. " +
                        "Open Search and let smart search index your archive first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

private suspend fun buildTopicClusters(
    whom: String,
    db: AppDatabase,
    windowMs: Long?,
): TopicsResult {
    val initial = clusterTopicsWithin(whom, db, windowMs)
    if (initial.isNotEmpty() || windowMs == null) {
        return TopicsResult(initial, fellBackToAllTime = false)
    }
    val fallback = clusterTopicsWithin(whom, db, windowMs = null)
    return TopicsResult(fallback, fellBackToAllTime = fallback.isNotEmpty())
}

private suspend fun clusterTopicsWithin(
    whom: String,
    db: AppDatabase,
    windowMs: Long?,
): List<TopicClusterRow> {
    val embeddings = db.embeddings().forWhom(whom)
    if (embeddings.size < 6) return emptyList()
    val cutoffMs = windowMs?.let { System.currentTimeMillis() - it }
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
