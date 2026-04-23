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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
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
import io.nisfeb.talon.ui.MentionPicker
import io.nisfeb.talon.ui.ReactionPalette
import io.nisfeb.talon.ui.StoryRenderer
import io.nisfeb.talon.ui.VoiceRecordButton
import io.nisfeb.talon.ui.contactMapFlow
import io.nisfeb.talon.ui.detectMentionQuery
import io.nisfeb.talon.ui.suggestionsFor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.nisfeb.talon.urbit.StoryCache
import io.nisfeb.talon.urbit.TlonChatRepo
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
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

    val listState = rememberLazyListState()

    // Pinned ↔ the last item's bottom is inside the viewport. Flips to
    // false the moment the user scrolls up, and back to true only once
    // they return to the bottom — which is exactly the follow-new-messages
    // behavior we want.
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

    // On first data arrival, either jump to a notification-specified
    // message (if it's in the loaded window) or anchor to the bottom.
    // Subsequent size changes follow only when pinned near the bottom.
    // `forceBottomTick` is bumped by the send handler to override the
    // pinned-check — user sent something, take them to their own message.
    var hasAnchored by remember(whom) { mutableStateOf(false) }
    var forceBottomTick by remember(whom) { mutableStateOf(0) }
    LaunchedEffect(rows.size, initialScrollMessageId, forceBottomTick) {
        if (rows.isEmpty()) return@LaunchedEffect
        if (!hasAnchored && initialScrollMessageId != null) {
            val idx = rows.indexOfFirst { item ->
                item is ChatListItem.Message && item.row.m.id == initialScrollMessageId
            }
            if (idx >= 0) {
                listState.scrollToItem(idx)
                hasAnchored = true
                onScrollConsumed()
                return@LaunchedEffect
            }
            // Target not in the loaded window — fall through to the
            // bottom-anchor so we don't leave the user stranded at top.
        }
        if (!hasAnchored || isPinnedToBottom || forceBottomTick > 0) {
            listState.scrollHardToBottom(rows.lastIndex)
            hasAnchored = true
        }
    }

    // Keep the bottom pinned while the IME is animating open/closed. The
    // Column itself shrinks from below via windowInsetsPadding(safeDrawing),
    // but the LazyColumn's scroll position is anchored to the *top*, so the
    // newest message drops out of the bottom edge unless we chase it.
    // Snapshot "was at bottom" before the IME state flips, because once
    // the viewport shrinks isPinnedToBottom immediately goes false.
    val isImeVisible = WindowInsets.isImeVisible
    var wasPinnedBeforeIme by remember(whom) { mutableStateOf(true) }
    LaunchedEffect(isPinnedToBottom, isImeVisible) {
        if (!isImeVisible) wasPinnedBeforeIme = isPinnedToBottom
    }
    LaunchedEffect(isImeVisible) {
        if (rows.isNotEmpty() && wasPinnedBeforeIme) {
            listState.scrollHardToBottom(rows.lastIndex)
        }
    }

    // Mark read whenever the user opens (or reopens) a conversation.
    LaunchedEffect(whom) {
        runCatching { repo.markRead(whom) }
    }

    // Scroll-back: when the user drags close to the top of the loaded
    // history, pull another page of older posts. Serialized on a flag so
    // we don't fan out a request per visible-item tick.
    var paginating by remember(whom) { mutableStateOf(false) }
    var paginationExhausted by remember(whom) { mutableStateOf(false) }
    LaunchedEffect(whom) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { idx ->
                if (idx < 4 && !paginating && !paginationExhausted && rows.isNotEmpty()) {
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

    var actionTarget by remember { mutableStateOf<MessageEntity?>(null) }
    var editing by remember { mutableStateOf<MessageEntity?>(null) }
    var confirmingDelete by remember { mutableStateOf<MessageEntity?>(null) }
    var profileSheetShip by remember { mutableStateOf<String?>(null) }

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

    val notifyPref by remember(whom) {
        db.notifyPrefs().stream(whom)
    }.collectAsState(initial = null)
    val notifyLevel = notifyPref?.level ?: NotifyLevel.DEFAULT

    Column(modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
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
                                db.notifyPrefs().upsert(
                                    NotifyPreferenceEntity(whom, NotifyLevel.ALL),
                                )
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (notifyLevel == NotifyLevel.MENTIONS) "✓ Mentions only" else "Mentions only") },
                        onClick = {
                            notifyMenuOpen = false
                            scope.launch {
                                db.notifyPrefs().upsert(
                                    NotifyPreferenceEntity(whom, NotifyLevel.MENTIONS),
                                )
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (notifyLevel == NotifyLevel.NONE) "✓ Mute" else "Mute") },
                        onClick = {
                            notifyMenuOpen = false
                            scope.launch {
                                db.notifyPrefs().upsert(
                                    NotifyPreferenceEntity(whom, NotifyLevel.NONE),
                                )
                            }
                        },
                    )
                }
            }
        }
        HorizontalDivider()
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            items(
                items = rows,
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
                        onMentionTap = onMentionTap,
                        onLinkTap = onLinkTap,
                        onImageTap = onImageTap,
                        onAvatarTap = onAvatarTap,
                        onCitationTap = onCitationTap,
                    )
                }
            }
        }
        HorizontalDivider()
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
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
                    uploading = true
                    sendError = null
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
                            sendError = "voice send failed: ${err.message ?: err::class.simpleName}"
                        }
                        file.delete()
                        uploading = false
                    }
                },
            )
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Message") },
                enabled = canSend && !uploading,
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    val body = draft.text.trim()
                    if (body.isEmpty() || !canSend) return@IconButton
                    draft = TextFieldValue("")
                    drafts.clear(whom)
                    sendError = null
                    forceBottomTick += 1
                    scope.launch {
                        runCatching { repo.send(whom, body) }
                            .onFailure { err -> sendError = "send failed: ${err.message ?: err::class.simpleName}" }
                    }
                },
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

    actionTarget?.let { target ->
        val isBookmarked by remember(target.whom, target.id) {
            db.bookmarks().isBookmarked(target.whom, target.id)
        }.collectAsState(initial = false)

        MessageActionSheet(
            message = target,
            ourPatp = ourPatp,
            isChannel = whom.startsWith("chat/"),
            isBookmarked = isBookmarked,
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
                        db.bookmarks().remove(target.whom, target.id)
                    } else {
                        db.bookmarks().upsert(
                            BookmarkEntity(
                                whom = target.whom,
                                postId = target.id,
                                bookmarkedMs = System.currentTimeMillis(),
                            )
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
        )
    }

    editing?.let { target ->
        EditMessageDialog(
            initial = StoryCache.textFor(target.id, target.contentJson),
            onDismiss = { editing = null },
            onSave = { newText ->
                editing = null
                scope.launch {
                    runCatching { repo.edit(whom, target.id, newText) }
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
                        runCatching { repo.delete(whom, target.id) }
                            .onFailure { sendError = "delete failed: ${it.message ?: it::class.simpleName}" }
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = null }) { Text("Cancel") }
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
    onMentionTap: (String) -> Unit,
    onLinkTap: (String) -> Unit,
    onImageTap: (String) -> Unit,
    onAvatarTap: (String) -> Unit,
    onCitationTap: (String) -> Unit,
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = { onLongPress(m) })
            .graphicsLayer { translationX = offsetX.value }
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
            )
            val firstLink = remember(parts) { firstLinkUrl(parts) }
            if (firstLink != null) {
                LinkPreviewCard(
                    url = firstLink,
                    onOpen = onLinkTap,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (grouped.isNotEmpty() || row.replyCount > 0) {
                FlowRow(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    grouped.forEach { (emoji, count, mine) ->
                        ReactionChip(emoji = emoji, count = count, mine = mine) {
                            onReactionTap(m, row.reactions, emoji)
                        }
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

@Composable
private fun ReactionChip(emoji: String, count: Int, mine: Boolean, onClick: () -> Unit) {
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
    onDismiss: () -> Unit,
    onPickReaction: (String) -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onToggleBookmark: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val isMine = message.author == ourPatp
    val canReply = message.parentId == null
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("React", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ReactionPalette.picker.forEach { (code, emoji) ->
                    Text(
                        emoji,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onPickReaction(code) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
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
            if (isMine) {
                if (isChannel) {
                    TextButton(onClick = onEdit) { Text("Edit") }
                }
                TextButton(onClick = onDelete) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
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

/**
 * Scrolls the LazyColumn hard against the bottom, retrying until the
 * layout reports it can't scroll any further forward. Needed because
 * message rows measure asynchronously (image decode, inline link
 * previews, reactions wrap) — a single scrollToItem + scrollBy fires
 * before the final heights are known and lands short of the true end.
 */
private suspend fun LazyListState.scrollHardToBottom(lastIndex: Int) {
    if (lastIndex < 0) return
    scrollToItem(lastIndex, Int.MAX_VALUE)
    // Chase the true bottom for ~500ms: after each frame, if more
    // layout has resolved and we can scroll further, do so. Eight
    // retries at 60fps gives async image decodes time to land.
    repeat(8) {
        kotlinx.coroutines.delay(60)
        if (!canScrollForward) return
        scrollBy(Float.MAX_VALUE)
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
