// TEMPORARY DUPLICATE of app/src/main/java/io/nisfeb/talon/ui/screens/DmChatScreen.kt
//
// This is a heavily reduced port that captures the screen's core
// rendering surface — header, reverse-layout message list, composer
// with mention / emoji / slash autocomplete + a swipe-to-reply gesture
// — but stubs out every feature that depended on TalonApplication
// internals, ML Kit, ExoPlayer, ActivityResultContracts, Android
// clipboard, or the Android-only sensor stack. Each stub carries a
// TODO(port-d5-followup) marker explaining what's missing.
//
// Stubbed in this port (each gated by a `// TODO(port-d5-followup):`):
//   - image / file pickers (rememberLauncherForActivityResult)
//   - voice recording (VoiceRecordButton, VoiceRecorder, ExoPlayer
//     preview row)
//   - location permission + /loc handler
//   - clipboard "Copy text"
//   - AI catch-me-up banner + AI emoji picker
//   - watchwords exclude/include menu entry
//   - topic clusters sheet (k-means)
//   - entity action chips (commonMain expect/actual already no-ops)
//   - link preview card on each row (no http client routed through yet
//     to the per-row position; the screen-level `runCommand` does take
//     one)
//   - bookmarks / pinned-post DB streams
//   - message action sheet (long-press menu) and edit/delete dialogs
//   - notification level dropdown
//
// Keep in sync with production until app/ is removed in Stage F.
package io.nisfeb.talon.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
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
import io.nisfeb.talon.ui.LocalCiteResolver
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
import kotlinx.coroutines.Dispatchers
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
    ourPatp: String,
    whom: String,
    initialScrollMessageId: String? = null,
    onScrollConsumed: () -> Unit = {},
    onBack: () -> Unit,
    onOpenThread: (parentId: String) -> Unit,
    onOpenConversation: (whom: String) -> Unit,
    onOpenImage: (url: String) -> Unit,
    onOpenSelfProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
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

    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty() && listState.firstVisibleItemIndex <= 12) {
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

    // ── message action sheet state ──
    var actionTarget by remember { mutableStateOf<MessageEntity?>(null) }
    var editing by remember { mutableStateOf<MessageEntity?>(null) }              // TODO(port-d5-followup): wire edit composer
    var confirmingDelete by remember { mutableStateOf<MessageEntity?>(null) }    // TODO(port-d5-followup): wire delete confirmation dialog
    var pendingQuote by remember(whom) { mutableStateOf<MessageEntity?>(null) }  // TODO(port-d5-followup): wire quote into composer

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
    val onLinkTap: (String) -> Unit = remember {
        // TODO(port-d5-followup): wire a platform URL opener; on Android
        // production launches Intent.ACTION_VIEW.
        { _ -> }
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
                // TODO(port-d5-followup): notification level dropdown,
                // topics sheet trigger, watchwords menu — all need
                // additional ctor params and the production AI/notify/
                // watchwords subsystems threaded through.
            }
            HorizontalDivider()
            if (refreshing && rows.isEmpty()) {
                androidx.compose.material3.LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
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
                            onLongPress = onLongPressMessage,
                            onOpenThread = onOpenThreadForMessage,
                            onReactionTap = onReactionForMessage,
                            onMentionTap = onMentionTap,
                            onLinkTap = onLinkTap,
                            onImageTap = currentOnOpenImage,
                            onAvatarTap = onAvatarTap,
                            onCitationTap = onCitationTap,
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
            // TODO(port-d5-followup): full composer in production has
            // image / file / mic buttons via ActivityResultContracts and
            // hooks into watchwords / AI / clipboard. The reduced
            // composer here covers plain-text + slash + mention + emoji.
            val doSend: () -> Boolean = {
                val body = draft.text.trim()
                val canEmit = body.isNotEmpty() && canSend
                if (!canEmit) {
                    false
                } else {
                    draft = TextFieldValue("")
                    drafts.clear(whom)
                    sendError = null
                    forceBottomTick += 1
                    scope.launch {
                        runCatching {
                            val cmd = runCommand(
                                rawText = body,
                                repo = repo,
                                http = http,
                                locationProvider = null,
                                toast = { msg -> sendError = msg },
                            )
                            when (cmd) {
                                is CommandResult.Send -> repo.send(whom, cmd.body)
                                is CommandResult.Handled -> {}
                                is CommandResult.Error -> sendError = cmd.message
                                is CommandResult.NotACommand -> repo.send(whom, body)
                            }
                        }.onFailure { err ->
                            Log.e("DmChatScreen", "send failed", err)
                            sendError = "send failed: ${err.message ?: err::class.simpleName}"
                        }
                    }
                    true
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
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
                    // TODO(port-d5-followup): wire edit composer
                },
                onDelete = {
                    actionTarget = null
                    confirmingDelete = target
                    // TODO(port-d5-followup): wire delete confirmation dialog
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
            )
        }
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
    onLongPress: (MessageEntity) -> Unit,
    onOpenThread: (MessageEntity) -> Unit,
    onReactionTap: (MessageEntity, List<ReactionEntity>, String) -> Unit,
    onMentionTap: (String) -> Unit,
    onLinkTap: (String) -> Unit,
    onImageTap: (String) -> Unit,
    onAvatarTap: (String) -> Unit,
    onCitationTap: (String) -> Unit,
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
            .combinedClickable(onClick = {}, onLongClick = { onLongPress(m) })
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
            // TODO(port-d5-followup): production renders LinkPreviewCard
            // for the first link in each row + EntityActionChips for ML
            // Kit-detected entities. Both need wiring through ctor here.
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
//  - AI emoji picker removed (aiConfigured / TalonApplication AI stack
//    is Android-only; TODO(port-d5-followup) when AI bridge is ported).
//  - windowInsetsPadding(WindowInsets.navigationBars) kept — no-ops on
//    desktop but harmless.

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
                // TODO(port-d5-followup): AI emoji picker — requires
                // AiSettings / EntityActions from the Android AI stack.
                // Add `showAiEmoji` + `onAiEmoji` params when ported.
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
