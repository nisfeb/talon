package io.nisfeb.talon.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Topic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.BookmarkEntity
import io.nisfeb.talon.data.NotifyLevel
import io.nisfeb.talon.data.NotifyPreferenceEntity
import io.nisfeb.talon.data.ReactionEntity
import io.nisfeb.talon.data.ReplyCount
import io.nisfeb.talon.TalonApplication
import io.nisfeb.talon.ui.Avatar
import io.nisfeb.talon.ui.ContactProfileSheet
import io.nisfeb.talon.ui.LinkPreviewCard
import io.nisfeb.talon.ui.firstLinkUrl
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.CommandResult
import io.nisfeb.talon.ui.EmojiCatalog
import io.nisfeb.talon.ui.EmojiPickerDropdown
import io.nisfeb.talon.ui.EntityActionChips
import io.nisfeb.talon.ui.MentionPicker
import io.nisfeb.talon.ui.ReactionPalette
import io.nisfeb.talon.ui.SlashPicker
import io.nisfeb.talon.ui.detectEmojiQuery
import io.nisfeb.talon.ui.detectSlashTrigger
import io.nisfeb.talon.ui.filterSlashCommands
import io.nisfeb.talon.ui.runCommand
import io.nisfeb.talon.ui.StoryRenderer
import io.nisfeb.talon.ui.VoiceRecordButton
import io.nisfeb.talon.ui.contactMapFlow
import io.nisfeb.talon.ui.detectMentionQuery
import io.nisfeb.talon.ui.suggestionsFor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.nisfeb.talon.urbit.StoryCache
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
@Composable
fun DmChatScreen(
    db: AppDatabase,
    repo: TlonChatRepo,
    ourPatp: String,
    whom: String,
    initialScrollMessageId: String? = null,
    onScrollConsumed: () -> Unit = {},
    onBack: () -> Unit,
    onOpenThread: (parentId: String) -> Unit,
    /** Open the thread for [parentId] and anchor the initial scroll on
     *  [replyAnchor]. Used by the topics-sheet deep-link when the
     *  cluster's representative is itself a reply. */
    onOpenThreadAt: (parentId: String, replyAnchor: String) -> Unit = { p, _ -> onOpenThread(p) },
    onOpenConversation: (whom: String) -> Unit,
    onOpenImage: (url: String) -> Unit,
    onOpenSelfProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows by remember(whom) {
        combine(
            db.messages().stream(whom),
            db.reactions().stream(whom),
            db.messages().streamReplyCounts(whom),
        ) { messages, reactions, replyCounts ->
            val reactsByPost = reactions.groupBy { it.postId }
            val countsByPost = replyCounts.associateBy(ReplyCount::postId)
            buildChatListItems(
                messages = messages,
                reactsByPost = reactsByPost,
                countsByPost = countsByPost,
            )
        }
    }.collectAsState(initial = emptyList())

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

    // The LazyColumn below uses `reverseLayout = true` with rows passed
    // in reverse order, so item index 0 is the newest message and is
    // drawn at the viewport's bottom. "Pinned to bottom" therefore means
    // firstVisibleItemIndex == 0 with no scroll offset, which is also
    // the default resting state — no chasing needed as items measure,
    // because async content only ever grows upward (visually) from the
    // anchor.
    val isPinnedToBottom by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset == 0
        }
    }

    // Notification deep-link: jump to the specified message. With
    // reversed data the target's reversed index = (lastIndex - originalIndex).
    var hasAnchored by remember(whom) { mutableStateOf(false) }
    LaunchedEffect(rows.size, initialScrollMessageId) {
        if (rows.isEmpty()) return@LaunchedEffect
        if (!hasAnchored && initialScrollMessageId != null) {
            val originalIdx = rows.indexOfFirst { item ->
                item is ChatListItem.Message && item.row.m.id == initialScrollMessageId
            }
            if (originalIdx >= 0) {
                listState.scrollToItem(rows.lastIndex - originalIdx)
                hasAnchored = true
                onScrollConsumed()
                return@LaunchedEffect
            }
            // Target not in the loaded window — fall through and let
            // reverseLayout land us at the newest (bottom).
        }
        hasAnchored = true
    }

    // Send-triggered: snap to the newest. With reverseLayout this is
    // trivially index 0; no chasing, no retry loop.
    var forceBottomTick by remember(whom) { mutableStateOf(0) }
    LaunchedEffect(forceBottomTick) {
        if (forceBottomTick > 0 && rows.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    // Auto-follow new incoming messages when the user is near the bottom.
    // With reverseLayout, "near the bottom" means firstVisibleItemIndex
    // close to 0. We tolerate up to 2 items of scroll so a tiny finger
    // wiggle doesn't strand the user off-feed.
    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty() && listState.firstVisibleItemIndex <= 2) {
            listState.scrollToItem(0)
        }
    }

    // IME open/close: with reverseLayout the bottom is index 0, so all
    // we need to do is re-anchor to 0 if the user was pinned. No chase.
    val isImeVisible = WindowInsets.isImeVisible
    var wasPinnedBeforeIme by remember(whom) { mutableStateOf(true) }
    LaunchedEffect(isPinnedToBottom, isImeVisible) {
        if (!isImeVisible) wasPinnedBeforeIme = isPinnedToBottom
    }
    LaunchedEffect(isImeVisible) {
        if (rows.isNotEmpty() && wasPinnedBeforeIme) {
            listState.scrollToItem(0)
        }
    }

    // Flatten the home list's cached unread snapshot for this whom so
    // back-out re-mount paints with 0 instantly rather than flashing the
    // stale pre-entry count. Cleanup of the open-chat ref happens here
    // too; the actual setOpenChat (which fires mark-read) is staged in
    // the LaunchedEffect below — see the comment there.
    DisposableEffect(whom) {
        homeSnapshotZeroUnread(whom)
        onDispose { repo.setOpenChat(null) }
    }

    // Backfill any messages that slipped through SSE (e.g. via bursts
    // that overflowed the old bounded channel buffer). Cheap scry,
    // idempotent upsert — no dupes, fills holes. Grab a wide window so
    // historical gaps are covered even on busy channels.
    LaunchedEffect(whom) {
        runCatching { repo.refreshConversation(whom, count = 500) }
    }

    // Scroll-back: when the user scrolls visually upward toward the
    // oldest loaded post, pull another page. With reverseLayout the
    // visual top is the HIGHEST reversed index, so we watch for the
    // last visible index approaching rows.lastIndex.
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
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val drafts = remember { (context.applicationContext as TalonApplication).drafts }
    var draft by remember(whom) { mutableStateOf(TextFieldValue(drafts.load(whom))) }

    // Persist draft edits so navigating away and coming back preserves
    // what the user was typing.
    LaunchedEffect(whom, draft.text) {
        drafts.save(whom, draft.text)
    }
    var sendError by remember(whom) { mutableStateOf<String?>(null) }
    var uploading by remember { mutableStateOf(false) }
    val canSend = remember(whom) {
        whom.startsWith("~") || whom.startsWith("0v") || whom.startsWith("chat/")
    }
    // Recorded-but-unsent voice clip. When non-null, the composer row is
    // replaced with a preview widget so the user can review and
    // send/discard rather than fire-and-forget.
    var pendingVoice by remember(whom) {
        mutableStateOf<PendingVoice?>(null)
    }
    // Clean up any stray recorded file if the user navigates away with
    // a preview still pending.
    DisposableEffect(whom) {
        onDispose {
            pendingVoice?.file?.delete()
        }
    }

    // Location permission launcher for `/loc`. When /loc runs without
    // the permission, we kick this off and tell the user to retry.
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) sendError = "Location granted — run /loc again"
        else sendError = "Location permission denied"
    }

    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null || !canSend) return@rememberLauncherForActivityResult
        uploading = true
        sendError = null
        scope.launch {
            runCatching {
                val resolver = context.contentResolver
                val mime = resolver.getType(uri) ?: "image/jpeg"
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "image"
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("cannot read image bytes")
                val dims = decodeDimensions(bytes)
                val hostedUrl = repo.uploadImage(bytes, mime, name)
                repo.sendImage(
                    whom = whom,
                    src = hostedUrl,
                    width = dims.first,
                    height = dims.second,
                    alt = name,
                )
            }.onFailure { err ->
                sendError = "image failed: ${err.message ?: err::class.simpleName}"
            }
            uploading = false
        }
    }

    val pickFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null || !canSend) return@rememberLauncherForActivityResult
        uploading = true
        sendError = null
        scope.launch {
            runCatching {
                val resolver = context.contentResolver
                val mime = resolver.getType(uri) ?: "application/octet-stream"
                val name = queryDisplayName(resolver, uri)
                    ?: uri.lastPathSegment?.substringAfterLast('/')
                    ?: "file"
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("cannot read file bytes")
                val hostedUrl = repo.uploadImage(bytes, mime, name)
                // Send as a markdown link; the parser turns it into a
                // link-span so it's tappable on every Tlon client.
                repo.send(whom, "[📎 $name]($hostedUrl)")
            }.onFailure { err ->
                sendError = "file failed: ${err.message ?: err::class.simpleName}"
            }
            uploading = false
        }
    }

    val contactList by remember {
        db.contacts().stream()
    }.collectAsState(initial = emptyList())

    // Pool of ships to suggest: conversation participants + every contact
    // we know about, de-duped.
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

    var actionTarget by remember { mutableStateOf<MessageEntity?>(null) }
    var topicsSheetOpen by remember(whom) { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MessageEntity?>(null) }
    var confirmingDelete by remember { mutableStateOf<MessageEntity?>(null) }
    var profileSheetShip by remember { mutableStateOf<String?>(null) }

    // /mic slash-triggered recording. VoiceRecordButton handles the
    // tap-bar case; this shadow state drives the parallel flow when a
    // user kicks off recording via the slash command instead.
    val slashRecorder = remember { io.nisfeb.talon.ui.VoiceRecorder(context) }
    var slashMicActive by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            // Make sure we never leak the mic if the screen unmounts
            // mid-recording (nav, rotation).
            if (slashMicActive) slashRecorder.cancel()
        }
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        sendError = if (granted) "Mic granted — run /mic again" else "Mic permission denied"
    }

    // AI: catch-me-up. Feature toggled on when a key is configured.
    // Banner appears when the chat has unreads and we haven't summarized
    // this visit yet. We snapshot unread on chat-open so marking-read
    // (which fires immediately) doesn't hide the banner mid-render.
    val talonApp = (context.applicationContext as TalonApplication)
    val aiConfigured by talonApp.aiSettings.state.collectAsState()
    var unreadSnapshot by remember(whom) { mutableStateOf<Int?>(null) }
    // Capture the pre-entry unread count BEFORE telling the repo this
    // chat is focused. setOpenChat launches markRead, which writes
    // count=0 to the unreads table — if the unread flow's first
    // emission lost the race against that write, the catch-me-up
    // banner would silently never appear. Sequencing both in the same
    // coroutine pins the order: read → snapshot → mark.
    androidx.compose.runtime.LaunchedEffect(whom) {
        if (unreadSnapshot == null) {
            unreadSnapshot = db.unreads().getOne(whom)?.count ?: 0
        }
        repo.setOpenChat(whom)
    }
    var catchUpSummary by remember(whom) { mutableStateOf<String?>(null) }
    var catchingUp by remember(whom) { mutableStateOf(false) }
    var catchUpError by remember(whom) { mutableStateOf<String?>(null) }
    var aiEmojiWorking by remember { mutableStateOf(false) }
    // Pending quote: when non-null, the composer shows a preview and
    // the next send prepends a cite block to the story so recipients
    // see "quoting <msg>" above the user's text. Cleared after send or
    // if the user dismisses the preview.
    var pendingQuote by remember(whom) { mutableStateOf<MessageEntity?>(null) }

    // Stable callbacks so MessageRow params don't flip identity on each
    // DmChatScreen recomposition; otherwise LazyColumn rows keep missing
    // the skippable-function fast path during scroll. rememberUpdatedState
    // lets the inner lambda keep a frozen identity while still calling
    // through to the freshest outer function.
    val currentOnOpenThread by rememberUpdatedState(onOpenThread)
    val currentOnOpenConversation by rememberUpdatedState(onOpenConversation)
    val currentOnOpenImage by rememberUpdatedState(onOpenImage)
    val onLongPressMessage: (MessageEntity) -> Unit = remember(haptic) {
        { m ->
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            actionTarget = m
        }
    }
    val onOpenThreadForMessage: (MessageEntity) -> Unit = remember {
        { m -> currentOnOpenThread(m.id) }
    }
    val onMentionTap: (String) -> Unit = remember {
        { patp -> profileSheetShip = patp }
    }
    val onAvatarTap: (String) -> Unit = remember {
        { patp -> profileSheetShip = patp }
    }
    val onLinkTap: (String) -> Unit = remember(context) {
        { href ->
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(href))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
    }
    val onImageTap: (String) -> Unit = remember {
        { url -> currentOnOpenImage(url) }
    }
    val onCitationTap: (String) -> Unit = remember {
        { target -> currentOnOpenConversation(target) }
    }
    val onReactionForMessage: (MessageEntity, List<ReactionEntity>, String) -> Unit =
        remember(ourPatp, whom, haptic) {
            { m, reactions, emoji ->
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                val mine = reactions.firstOrNull { it.author == ourPatp }?.emoji
                scope.launch {
                    runCatching {
                        if (mine == emoji) repo.unreact(whom, m.id)
                        else repo.react(whom, m.id, emoji)
                    }.onFailure {
                        sendError = "react failed: ${it.message ?: it::class.simpleName}"
                    }
                }
            }
        }
    // Long-press a reaction chip → show the full who-reacted list.
    var reactionDetails by remember { mutableStateOf<List<ReactionEntity>?>(null) }
    val onReactionLongPressForMessage: (MessageEntity, List<ReactionEntity>) -> Unit =
        remember(haptic) {
            { _, reactions ->
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                reactionDetails = reactions
            }
        }

    val notifyPref by remember(whom) {
        db.notifyPrefs().stream(whom)
    }.collectAsState(initial = null)
    val notifyLevel = notifyPref?.level ?: NotifyLevel.DEFAULT

    Column(
        modifier = modifier
            .systemBarsPadding()
            .imePadding(),
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
            // Topics sheet trigger — opens the k-means clusters
            // panel below. State hoisted at top of DmChatScreen so
            // the sheet rendering at the bottom of the file can
            // observe it.
            if (aiConfigured.topicClustersEnabled) {
                IconButton(onClick = { topicsSheetOpen = true }) {
                    Icon(Icons.Filled.Topic, contentDescription = "Topics in this chat")
                }
            }
            var notifyMenuOpen by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { notifyMenuOpen = true }) {
                    Icon(
                        imageVector = if (notifyLevel == NotifyLevel.NONE)
                            Icons.Filled.NotificationsOff
                        else Icons.Filled.Notifications,
                        contentDescription = "Notifications",
                    )
                }
                DropdownMenu(
                    expanded = notifyMenuOpen,
                    onDismissRequest = { notifyMenuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(if (notifyLevel == NotifyLevel.ALL) "✓ All messages" else "All messages") },
                        onClick = {
                            notifyMenuOpen = false
                            scope.launch {
                                repo.settingsSync.setNotifyLevel(whom, NotifyLevel.ALL)
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (notifyLevel == NotifyLevel.MENTIONS) "✓ Mentions only" else "Mentions only") },
                        onClick = {
                            notifyMenuOpen = false
                            scope.launch {
                                repo.settingsSync.setNotifyLevel(whom, NotifyLevel.MENTIONS)
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (notifyLevel == NotifyLevel.NONE) "✓ Mute" else "Mute") },
                        onClick = {
                            notifyMenuOpen = false
                            scope.launch {
                                repo.settingsSync.setNotifyLevel(whom, NotifyLevel.NONE)
                            }
                        },
                    )
                }
            }
        }
        HorizontalDivider()

        // Catch-me-up banner. Shown only when AI is configured + enabled
        // for this feature + chat has unreads we haven't summarized yet.
        val showCatchUp = aiConfigured.hasKey() &&
            aiConfigured.catchMeUpEnabled &&
            (unreadSnapshot ?: 0) > 0 &&
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
                            // latestFor returns newest-first; the prompt
                            // reads more naturally in chronological order.
                            val ordered = latest.asReversed()
                            talonApp.ai.catchMeUp(ordered) { patp ->
                                contactMap.displayName(patp)
                            }
                        }.onSuccess { catchUpSummary = it }
                            .onFailure { catchUpError = it.message ?: it::class.simpleName }
                        catchingUp = false
                    }
                },
            )
        }
        pinnedPostId?.let { pinId ->
            PinnedPostBanner(
                whom = whom,
                postId = pinId,
                db = db,
                contactMap = contactMap,
                onTap = {
                    val idx = rows.indexOfFirst {
                        it is ChatListItem.Message && it.row.m.id == pinId
                    }
                    if (idx >= 0) {
                        val reverseIdx = rows.size - 1 - idx
                        scope.launch { listState.animateScrollToItem(reverseIdx) }
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
                items = rows.asReversed(),
                key = { it.key },
                contentType = { it.contentType },
            ) { item ->
                when (item) {
                    is ChatListItem.DateDivider -> DateDividerRow(item.label)
                    is ChatListItem.Message -> MessageRow(
                        row = item.row,
                        ourPatp = ourPatp,
                        contactMap = contactMap,
                        onLongPress = onLongPressMessage,
                        onOpenThread = onOpenThreadForMessage,
                        onReactionTap = onReactionForMessage,
                        onReactionLongPress = onReactionLongPressForMessage,
                        onMentionTap = onMentionTap,
                        onLinkTap = onLinkTap,
                        onImageTap = onImageTap,
                        onAvatarTap = onAvatarTap,
                        onCitationTap = onCitationTap,
                        entityActionsEnabled = aiConfigured.entityActionsEnabled,
                    )
                }
            }
        }
        HorizontalDivider()
        if (slashSuggestions.isNotEmpty() && slashTrigger != null) {
            SlashPicker(
                suggestions = slashSuggestions,
                onPick = { cmd ->
                    // Replace the typed "/query" fragment with "/name ".
                    val caret = draft.selection.start
                    val after = draft.text.substring(caret)
                    val inserted = "/${cmd.name} "
                    val newText = inserted + after
                    val newCaret = inserted.length
                    draft = TextFieldValue(
                        text = newText,
                        selection = TextRange(newCaret),
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
        if (sendError != null) {
            Text(
                sendError!!,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        pendingQuote?.let { q ->
            QuotePreviewRow(
                target = q,
                contactMap = contactMap,
                onDismiss = { pendingQuote = null },
            )
        }
        if (slashMicActive) {
            SlashMicRecordingBar(
                onStop = {
                    val result = slashRecorder.stop()
                    slashMicActive = false
                    if (result != null) {
                        val (file, durMs) = result
                        if (durMs < 300) {
                            file.delete()
                            sendError = "Too short — hold longer"
                        } else {
                            pendingVoice = PendingVoice(file, durMs)
                        }
                    } else {
                        sendError = "Recording failed"
                    }
                },
                onCancel = {
                    slashRecorder.cancel()
                    slashMicActive = false
                },
            )
        }
        // Voice preview swaps in instead of the normal composer while
        // the user is reviewing a just-recorded clip.
        pendingVoice?.let { pv ->
            VoicePreviewRow(
                pending = pv,
                sending = uploading,
                onCancel = {
                    pv.file.delete()
                    pendingVoice = null
                },
                onSend = {
                    uploading = true
                    sendError = null
                    val file = pv.file
                    val durationMs = pv.durationMs
                    pendingVoice = null
                    scope.launch {
                        runCatching {
                            val bytes = file.readBytes()
                            val hostedUrl = repo.uploadImage(
                                bytes = bytes,
                                contentType = "audio/mp4",
                                fileName = file.name,
                            )
                            val seconds = (durationMs / 1000L).coerceAtLeast(1L)
                            val label = "🎙 Voice ${seconds}s"
                            repo.send(whom, "[$label]($hostedUrl)")
                        }.onFailure { err ->
                            android.util.Log.e("DmChatScreen", "voice send failed", err)
                            sendError = "voice send failed: ${err.message ?: err::class.simpleName}"
                        }
                        file.delete()
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
            val hideComposerButtons by talonApp.uiSettings.hideComposerButtons
                .collectAsState()
            if (!hideComposerButtons) {
                IconButton(
                    onClick = {
                        pickImage.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    enabled = canSend && !uploading,
                    modifier = Modifier.size(36.dp),
                ) {
                    if (uploading) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp),
                        )
                    } else {
                        Icon(
                            Icons.Outlined.Image,
                            contentDescription = "Send image",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                IconButton(
                    onClick = { pickFile.launch("*/*") },
                    enabled = canSend && !uploading,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Filled.AttachFile,
                        contentDescription = "Attach file",
                        modifier = Modifier.size(22.dp),
                    )
                }
                VoiceRecordButton(
                    enabled = canSend && !uploading,
                    onRecorded = { file, durationMs ->
                        // Stage the recording in a preview row; user either
                        // listens + sends or discards.
                        pendingVoice = PendingVoice(file, durationMs)
                    },
                )
            }
            val doSend: () -> Boolean = {
                val body = draft.text.trim()
                val quote = pendingQuote
                // Allow sending a bare quote with no accompanying text.
                val canEmit = (body.isNotEmpty() || quote != null) && canSend && !uploading
                if (!canEmit) {
                    false
                } else {
                    draft = TextFieldValue("")
                    drafts.clear(whom)
                    sendError = null
                    forceBottomTick += 1
                    pendingQuote = null
                    // Intercept the UI-dispatched commands (pickers /
                    // recorder / permission prompts) before the coroutine
                    // dispatcher. Pure handlers run in scope.launch below.
                    val firstWord = body.trim().lowercase().substringBefore(' ')
                    val handledInUi = when {
                        quote != null -> false
                        firstWord == "/img" -> {
                            pickImage.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                            true
                        }
                        firstWord == "/file" -> {
                            pickFile.launch("*/*")
                            true
                        }
                        firstWord == "/mic" -> {
                            val granted = androidx.core.content.ContextCompat
                                .checkSelfPermission(
                                    context,
                                    android.Manifest.permission.RECORD_AUDIO,
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            if (!granted) {
                                micPermissionLauncher.launch(
                                    android.Manifest.permission.RECORD_AUDIO,
                                )
                                draft = TextFieldValue(body, TextRange(body.length))
                            } else {
                                runCatching {
                                    slashRecorder.start()
                                    slashMicActive = true
                                }.onFailure { err ->
                                    sendError =
                                        "mic start failed: ${err.message ?: err::class.simpleName}"
                                }
                            }
                            true
                        }
                        firstWord == "/loc" &&
                            !io.nisfeb.talon.ui.hasLocationPermission(context) -> {
                            locationPermissionLauncher.launch(
                                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                            )
                            draft = TextFieldValue(body, TextRange(body.length))
                            true
                        }
                        else -> false
                    }
                    if (!handledInUi) scope.launch {
                        runCatching {
                            val cmd = if (quote == null) {
                                runCommand(body, repo, talonApp.http, context) { msg ->
                                    sendError = msg
                                }
                            } else CommandResult.NotACommand
                            when (cmd) {
                                is CommandResult.Send -> repo.send(whom, cmd.body)
                                is CommandResult.Handled -> { /* command did its work */ }
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
                            android.util.Log.e("DmChatScreen", "send failed", err)
                            sendError = "send failed: ${err.message ?: err::class.simpleName}"
                        }
                    }
                    true
                }
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Message") },
                enabled = canSend && !uploading,
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    // Hardware keyboard: Enter sends, Shift+Enter adds a
                    // newline (fall-through to default IME behavior).
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
                enabled = canSend && !uploading && draft.text.isNotBlank(),
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

    if (topicsSheetOpen) {
        TopicsSheet(
            whom = whom,
            db = db,
            onDismiss = { topicsSheetOpen = false },
            onTapMessage = { msgId, parentId ->
                topicsSheetOpen = false
                if (parentId != null) {
                    // Reply — open its thread anchored on the
                    // specific reply the user tapped (not the newest).
                    onOpenThreadAt(parentId, msgId)
                } else {
                    val idx = rows.indexOfFirst {
                        it is ChatListItem.Message && it.row.m.id == msgId
                    }
                    if (idx >= 0) {
                        val reverseIdx = rows.size - 1 - idx
                        scope.launch { listState.animateScrollToItem(reverseIdx) }
                    }
                }
            },
        )
    }

    actionTarget?.let { target ->
        val isBookmarked by remember(target.whom, target.id) {
            db.bookmarks().isBookmarked(target.whom, target.id)
        }.collectAsState(initial = false)

        MessageActionSheet(
            message = target,
            ourPatp = ourPatp,
            isChannel = whom.startsWith("chat/"),
            isBookmarked = isBookmarked,
            isPinned = pinnedPostId == target.id,
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
                val clipboard = context.getSystemService(
                    android.content.Context.CLIPBOARD_SERVICE,
                ) as? android.content.ClipboardManager
                clipboard?.setPrimaryClip(
                    android.content.ClipData.newPlainText("message", text),
                )
            },
            onToggleBookmark = {
                actionTarget = null
                scope.launch {
                    if (isBookmarked) {
                        repo.settingsSync.removeBookmark(target.whom, target.id)
                    } else {
                        repo.settingsSync.addBookmark(
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
            showAiEmoji = aiConfigured.hasKey() && aiConfigured.emojiReactEnabled,
            aiEmojiWorking = aiEmojiWorking,
            onAiEmoji = {
                if (aiEmojiWorking) return@MessageActionSheet
                aiEmojiWorking = true
                scope.launch {
                    runCatching {
                        val text = StoryCache.textFor(target.id, target.contentJson)
                        talonApp.ai.suggestEmojiReact(text)
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

    confirmingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmingDelete = null },
            title = { Text("Delete this message?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = null
                    scope.launch {
                        runCatching {
                            repo.delete(whom, target.id, parentId = target.parentId)
                        }.onFailure {
                            sendError = "delete failed: ${it.message ?: it::class.simpleName}"
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = null }) { Text("Cancel") }
            },
        )
    }

    catchUpSummary?.let { summary ->
        AlertDialog(
            onDismissRequest = { catchUpSummary = null },
            title = { Text("Catch me up") },
            text = {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { catchUpSummary = null }) { Text("Got it") }
            },
        )
    }

    reactionDetails?.let { list ->
        ReactionDetailsSheet(
            reactions = list,
            contactMap = contactMap,
            onDismiss = { reactionDetails = null },
            onOpenProfile = { ship ->
                reactionDetails = null
                profileSheetShip = ship
            },
        )
    }

    catchUpError?.let { err ->
        AlertDialog(
            onDismissRequest = { catchUpError = null },
            title = { Text("AI error") },
            text = { Text(err) },
            confirmButton = {
                TextButton(onClick = { catchUpError = null }) { Text("OK") }
            },
        )
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
    onReactionLongPress: (MessageEntity, List<ReactionEntity>) -> Unit,
    onMentionTap: (String) -> Unit,
    onLinkTap: (String) -> Unit,
    onImageTap: (String) -> Unit,
    onAvatarTap: (String) -> Unit,
    onCitationTap: (String) -> Unit,
    entityActionsEnabled: Boolean,
) {
    val haptic = LocalHapticFeedback.current
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

    // Swipe left to open the thread — standard messaging-app gesture.
    // Below the threshold the row rubber-bands back; past it, we fire.
    val offsetX = remember { androidx.compose.runtime.mutableStateOf(0f) }
    // "local_*" ids mark optimistic rows that haven't been echoed by the
    // server yet — dim them so the user sees which messages are in
    // flight vs confirmed.
    val isPending = remember(m.id) { m.id.startsWith("local_") }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = { onLongPress(m) })
            .graphicsLayer {
                translationX = offsetX.value
                alpha = if (isPending) 0.55f else 1f
            }
            .pointerInput(m.id) {
                var crossedThreshold = false
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val fired = offsetX.value < -SWIPE_REPLY_THRESHOLD_PX
                        offsetX.value = 0f
                        crossedThreshold = false
                        if (fired) onOpenThread(m)
                    },
                    onDragCancel = {
                        offsetX.value = 0f
                        crossedThreshold = false
                    },
                    onHorizontalDrag = { _, dx ->
                        val next = (offsetX.value + dx).coerceIn(-SWIPE_REPLY_MAX_PX, 0f)
                        offsetX.value = next
                        // One-shot haptic the moment we cross the
                        // threshold, confirming the gesture will fire.
                        if (!crossedThreshold && next < -SWIPE_REPLY_THRESHOLD_PX) {
                            crossedThreshold = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        } else if (crossedThreshold && next > -SWIPE_REPLY_THRESHOLD_PX) {
                            crossedThreshold = false
                        }
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
                    onOpen = onLinkTap,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            // On-device entity-action chips: dates → calendar,
            // addresses → maps, phones → dialer, emails → mail.
            // Pulls the message's plain text from StoryCache so it
            // sees the same string the user sees, sans markdown.
            // Gated on the user opting in (off by default).
            if (entityActionsEnabled) {
                val plainText = remember(m.id, m.contentJson) {
                    StoryCache.textFor(m.id, m.contentJson)
                }
                EntityActionChips(
                    text = plainText,
                    modifier = Modifier.padding(top = 4.dp),
                )
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
                            onLongClick = { onReactionLongPress(m, row.reactions) },
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
    onLongClick: () -> Unit = {},
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
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
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

@OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
@Composable
private fun MessageActionSheet(
    message: MessageEntity,
    ourPatp: String,
    isChannel: Boolean,
    isBookmarked: Boolean,
    isPinned: Boolean,
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
                // Software nav bars on older Androids / gesture phones
                // without bottom insets-handling push the last button
                // under the system area otherwise.
                .windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val app = (LocalContext.current.applicationContext as TalonApplication)
            val topUsage by remember {
                app.db.reactionUsage().streamTop(8)
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
            TextButton(onClick = onToggleBookmark) {
                Text(if (isBookmarked) "Remove bookmark" else "Bookmark")
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
            // Admin-gated server-side; we show the button optimistically
            // and let the ship NACK for non-admins (same pattern as
            // channel-delete above).
            if (isChannel && message.parentId == null) {
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

private val TIME_FORMAT = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())

private val AVATAR_SIZE = 36.dp
private const val GROUP_GAP_MS = 5L * 60_000L

/**
 * Mixed row type for the LazyColumn. Date dividers are stable across
 * emissions via a deterministic key derived from the day string, so
 * Compose reuses the divider's node when messages around it change.
 */
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
}

private fun buildChatListItems(
    messages: List<MessageEntity>,
    reactsByPost: Map<String, List<ReactionEntity>>,
    countsByPost: Map<String, ReplyCount>,
): List<ChatListItem> {
    if (messages.isEmpty()) return emptyList()
    val out = ArrayList<ChatListItem>(messages.size + 8)
    val cal = java.util.Calendar.getInstance()
    var lastDayKey: String? = null
    var prev: MessageEntity? = null
    for (m in messages) {
        val dayKey = dayKeyFor(cal, m.sentMs)
        if (dayKey != lastDayKey) {
            out.add(ChatListItem.DateDivider(label = dividerLabel(m.sentMs), dayKey = dayKey))
            lastDayKey = dayKey
            // A new day forces a fresh author header even if the prior
            // message was from the same author within the gap window.
            prev = null
        }
        val showHeader = prev == null ||
            prev!!.author != m.author ||
            (m.sentMs - prev!!.sentMs) > GROUP_GAP_MS
        prev = m
        out.add(
            ChatListItem.Message(
                DisplayRow(
                    m = m,
                    reactions = reactsByPost[m.id].orEmpty(),
                    replyCount = countsByPost[m.id]?.count ?: 0,
                    showHeader = showHeader,
                )
            )
        )
    }
    return out
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

// Px thresholds for the swipe-to-reply gesture. These are density-independent
// enough that 60 / 120 px works well on 2–3 xxhdpi Pixel-class devices.
private const val SWIPE_REPLY_THRESHOLD_PX = 180f
private const val SWIPE_REPLY_MAX_PX = 320f

/**
 * One pre-grouped row in the chat. `showHeader` means this row opens a
 * new author+time block; otherwise it collapses under the previous row
 * (no author, no avatar — just the body).
 */
@androidx.compose.runtime.Immutable
private data class DisplayRow(
    val m: MessageEntity,
    val reactions: List<ReactionEntity>,
    val replyCount: Int,
    val showHeader: Boolean,
)

/**
 * Best-effort display name for a content:// URI. Returns null when the
 * provider doesn't expose OpenableColumns (rare but possible).
 */
private fun queryDisplayName(
    resolver: android.content.ContentResolver,
    uri: android.net.Uri,
): String? = runCatching {
    resolver.query(
        uri,
        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
        null, null, null,
    )?.use { c ->
        if (c.moveToFirst()) {
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            val v = if (idx >= 0) c.getString(idx) else null
            v?.takeIf { it.isNotBlank() }
        } else null
    }
}.getOrNull()

/**
 * Read just the image header to extract width + height without
 * allocating a full bitmap. Returns (0, 0) if the bytes don't decode.
 */
private fun decodeDimensions(bytes: ByteArray): Pair<Int, Int> {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    val w = if (opts.outWidth > 0) opts.outWidth else 0
    val h = if (opts.outHeight > 0) opts.outHeight else 0
    return w to h
}

/** Recorded-but-unsent voice clip, waiting on user review. */
private data class PendingVoice(val file: java.io.File, val durationMs: Long)

/**
 * Mini player + cancel/send controls that replaces the normal composer
 * while the user reviews a just-recorded voice note.
 */
@Composable
private fun VoicePreviewRow(
    pending: PendingVoice,
    sending: Boolean,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    val context = LocalContext.current
    // Use ExoPlayer for consistent playback with the inline audio player
    // used elsewhere in chat.
    val player = remember(pending.file) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(
                android.net.Uri.fromFile(pending.file)
            ))
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    var isPlaying by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) { isPlaying = p }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    val seconds = (pending.durationMs / 1000L).coerceAtLeast(1L)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(
            onClick = {
                if (isPlaying) player.pause()
                else {
                    // Restart from 0 if we finished playing last time.
                    if (player.currentPosition >= player.duration.coerceAtLeast(1L)) {
                        player.seekTo(0L)
                    }
                    player.play()
                }
            },
            enabled = !sending,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = if (isPlaying)
                    Icons.Filled.Pause
                else
                    Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            "🎙 ${seconds}s",
            style = MaterialTheme.typography.bodyMedium,
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
                imageVector = Icons.Filled.Close,
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
                    imageVector = Icons.AutoMirrored.Filled.Send,
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

/**
 * Topics-in-this-chat panel. Reads the cached embeddings for [whom],
 * runs k-means clustering, and renders each cluster as a row with the
 * representative message + cluster size. Tap a row to scroll the
 * chat to that message.
 *
 * Returns nothing while the embedding pass is loading or when the
 * archive doesn't have enough indexed messages to cluster meaningfully.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopicsSheet(
    whom: String,
    db: AppDatabase,
    onDismiss: () -> Unit,
    /** Called with (messageId, parentId-or-null) when the user taps a
     *  cluster row. Caller routes top-level posts to a chat scroll and
     *  reply messages to a thread deep-link. */
    onTapMessage: (id: String, parentId: String?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var loading by remember(whom) { mutableStateOf(true) }
    var clusters by remember(whom) { mutableStateOf<List<TopicClusterRow>>(emptyList()) }

    LaunchedEffect(whom) {
        loading = true
        clusters = withContext(Dispatchers.Default) { buildTopicClusters(whom, db) }
        loading = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Topics in this chat", style = MaterialTheme.typography.titleMedium)
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

private data class TopicClusterRow(
    val representativeId: String,
    /** Non-null when the representative is a reply — caller should
     *  open the thread keyed by this parent rather than scrolling
     *  the main chat. */
    val representativeParentId: String?,
    val representativeText: String,
    val count: Int,
)

/**
 * Worker for [TopicsSheet]: read embeddings + their messages, drop
 * one-word filler ("hmmm", "ok", "lol"), run k-means, return the
 * per-cluster representative + count, sorted largest-first.
 *
 * Two changes from the original "closest-to-centroid" version that
 * fix the "top topic is 'hmmm'" failure mode:
 *   1. Pre-filter: messages under 20 chars or 4 words don't enter
 *      clustering at all. Acknowledgments don't deserve a topic.
 *   2. Per-cluster representative is the LONGEST message in that
 *      cluster, not the centroid-closest one. The centroid of a
 *      mostly-generic cluster is itself generic, so closest-to-
 *      centroid picks the most generic message; longest carries
 *      the most information and gives a recognizable snippet.
 */
private suspend fun buildTopicClusters(
    whom: String,
    db: AppDatabase,
): List<TopicClusterRow> {
    val embeddings = db.embeddings().forWhom(whom)
    if (embeddings.size < 6) return emptyList()
    data class Row(
        val embedding: io.nisfeb.talon.data.MessageEmbeddingEntity,
        val message: io.nisfeb.talon.data.MessageEntity,
        val text: String,
    )
    val rows = embeddings.mapNotNull { e ->
        val msg = db.messages().getOne(e.whom, e.id) ?: return@mapNotNull null
        if (msg.isDeleted) return@mapNotNull null
        val text = io.nisfeb.talon.urbit.StoryCache
            .textFor(msg.id, msg.contentJson)
            .replace('\n', ' ')
            .trim()
        val wordCount = text.split(Regex("\\s+")).count { it.isNotBlank() }
        if (text.length < 20 || wordCount < 4) return@mapNotNull null
        Row(e, msg, text)
    }
    if (rows.size < 6) return emptyList()

    val vectors = rows.map {
        io.nisfeb.talon.ai.Embedder.unpack(it.embedding.vector, it.embedding.dim)
    }
    val k = (rows.size / 8).coerceIn(3, 6)
    val assignment = io.nisfeb.talon.ai.kMeansAssign(vectors, k)

    val out = mutableListOf<TopicClusterRow>()
    for (c in 0 until k) {
        val members = rows.indices.filter { assignment[it] == c }
        // Singleton clusters aren't a "topic" — skip them so the
        // panel doesn't show one-off messages alongside real groups.
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
 * Pinned post banner rendered above the channel's message list when
 * an admin has pinned a post. Shows author + preview; taps scroll the
 * list to the pinned message.
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
        StoryCache.textFor(m.id, m.contentJson).take(200).replace('\n', ' ')
    }
    val author = remember(m.author, contactMap) { contactMap.displayName(m.author) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Filled.PushPin,
            contentDescription = "Pinned",
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Pinned · $author",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (preview.isNotBlank()) {
                Text(
                    preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReactionDetailsSheet(
    reactions: List<ReactionEntity>,
    contactMap: ContactMap,
    onDismiss: () -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    // Group by author so multi-reaction (rare but possible) collapses.
    val sorted = remember(reactions) {
        reactions.sortedBy { contactMap.displayName(it.author).lowercase() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Reactions",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(bottom = 4.dp),
            )
            HorizontalDivider()
            sorted.forEach { r ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenProfile(r.author) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val label = contactMap.displayName(r.author)
                    Avatar(
                        label = label,
                        url = contactMap.avatar(r.author),
                        colorHex = contactMap.shipColor(r.author),
                        size = 32.dp,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                        )
                        if (label != r.author) {
                            Text(
                                r.author,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                    Text(
                        ReactionPalette.display(r.emoji),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun SlashMicRecordingBar(
    onStop: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Text(
            "Recording…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCancel) { Text("Cancel") }
        TextButton(onClick = onStop) { Text("Stop") }
    }
}

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
