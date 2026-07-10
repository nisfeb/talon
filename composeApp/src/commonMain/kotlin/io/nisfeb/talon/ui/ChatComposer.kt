package io.nisfeb.talon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.urbit.StoryCache
import io.nisfeb.talon.urbit.Presence
import io.nisfeb.talon.urbit.TlonChatRepo
import io.nisfeb.talon.util.Log
import io.nisfeb.talon.util.decodeImageDimensions
import io.nisfeb.talon.util.rememberImagePicker
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Shared chat composer used by [DmChatScreen]'s main message input and
 * by [ThreadList]'s reply input. Owns the full input experience —
 * mention picker, emoji picker, slash commands, image / file / voice
 * attach, quote preview, Enter-to-send / Shift+Enter-newline. Two
 * dispatch differences between the surfaces (DM sends a top-level
 * post; thread sends a reply) are abstracted via [ChatSendStrategy].
 *
 * Why one composable: anytime the DM composer grows a feature, the
 * thread composer used to silently fall behind. Sharing the body
 * means a feature added here lights up both surfaces in lockstep.
 */

/** The one thing an attachment can't recover from: no bytes. A cloud photo
 *  that never downloaded reads as a valid-but-empty stream, and uploading it
 *  posts a message pointing at a blank object. */
private const val EMPTY_ATTACHMENT_ERROR =
    "that came back empty (0 bytes) — if it's an online/cloud photo, open it " +
        "in your gallery first so it downloads, then try again"

/** A staged voice recording awaiting send confirmation. */
data class PendingVoice(val path: String, val durationMs: Long)

/**
 * A staged image / file awaiting send confirmation — the "review
 * before posting" step so the user can see they picked the right
 * thing before it goes out. Bytes are already read + validated; the
 * upload + send happens when they confirm. Held in memory only (no
 * temp file to clean up, unlike [PendingVoice]).
 */
data class PendingAttachment(
    val bytes: ByteArray,
    val mimeType: String,
    val displayName: String,
    val isImage: Boolean,
) {
    // ByteArray in a data class defaults to reference equals/hashCode;
    // this flows through Compose state once and disappears, so identity
    // is the right (and cheapest) comparison. Mirrors PickedImage.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * Mutable composer state hoisted out of the screen body so the
 * composer can operate on it and the screen can react to it. The
 * fields are deliberately public-`var` so callers can poke them
 * (e.g. an action sheet's "Quote" entry sets [pendingQuote]; an
 * out-of-band error path writes [sendError]).
 */
@Stable
class ComposerState(initialDraftText: String) {
    var draft by mutableStateOf(TextFieldValue(initialDraftText))
    var pendingQuote by mutableStateOf<MessageEntity?>(null)
    var pendingVoice by mutableStateOf<PendingVoice?>(null)
    var pendingAttachment by mutableStateOf<PendingAttachment?>(null)
    var sendError by mutableStateOf<String?>(null)
    var uploading by mutableStateOf(false)
}

/**
 * Re-keys on [whom] so switching conversations starts the composer
 * fresh. Loads any persisted draft text from [drafts].
 */
@Composable
fun rememberComposerState(whom: String, drafts: DraftStore): ComposerState =
    remember(whom) { ComposerState(drafts.load(whom)) }

/**
 * Per-surface dispatch. DM sends top-level posts; thread sends
 * replies. The composer doesn't care which — it just builds the
 * payload and asks the strategy to send.
 *
 * `supportsQuote` lets the composer fall back to a plain text send
 * when the strategy can't carry a quote (today threads have no
 * `replyQuote` on the wire, so quote-into-thread isn't supported).
 */
interface ChatSendStrategy {
    suspend fun sendText(text: String)

    /** Send a structured image. Both surfaces carry a full story —
     *  DM/post via repo.sendImage, thread via repo.replyImage — so the
     *  image renders inline rather than as a link. */
    suspend fun sendImage(src: String, width: Int, height: Int, alt: String)

    val supportsQuote: Boolean

    /** Only invoked when [supportsQuote] is true. */
    suspend fun sendQuote(body: String, quoteWhom: String, quoteId: String)
}

@Composable
fun ChatComposer(
    state: ComposerState,
    db: AppDatabase,
    repo: TlonChatRepo,
    http: OkHttpClient,
    drafts: DraftStore,
    whom: String,
    contactMap: ContactMap,
    /** All ships eligible for `@` autocomplete. Caller computes from
     *  rows + contacts so the picker can suggest people from this
     *  surface even before they've been added to contacts. */
    allShips: List<String>,
    canSend: Boolean,
    hideComposerButtons: Boolean,
    placeholder: String = "Message",
    locationProvider: LocationProvider? = null,
    voiceComposer: (@Composable (
        enabled: Boolean,
        onRecorded: (path: String, durationMs: Long) -> Unit,
    ) -> Unit)? = null,
    voicePlayer: (@Composable (path: String, sending: Boolean) -> Unit)? = null,
    /** Triggered when `/mic` is sent. Android starts the recorder;
     *  desktop has no recorder and the composer surfaces a
     *  user-facing "tap the mic button" error. */
    onSlashMic: (() -> Unit)? = null,
    /** Per-device opt-in for the `/poke` advanced surface. Defaults
     *  off; off → /poke returns "enable in Settings" instead of
     *  poking. Caller threads `uiSettings.powerFeaturesEnabled`
     *  through. */
    powerFeaturesEnabled: Boolean = false,
    /** Up-arrow-on-empty-composer hook: edit your most recently sent
     *  message (Slack/Discord convenience for hardware keyboards). Null
     *  where editing isn't supported (e.g. %chat DMs), in which case Up
     *  keeps its normal caret behaviour. */
    onEditLast: (() -> Unit)? = null,
    /** Caller-side hook fired right before the optimistic upsert
     *  lands. DM uses this to capture its scroll baseline + bump
     *  the force-bottom tick so its self-send-scroll heuristic sees
     *  the same row count the user saw at send time. Threads can
     *  pass an empty lambda or wire their own scroll bookkeeping. */
    onBeforeLocalEcho: () -> Unit = {},
    strategy: ChatSendStrategy,
) {
    val scope = rememberCoroutineScope()
    val pickImage = rememberImagePicker()
    val pickAnyFile = io.nisfeb.talon.util.rememberAnyFilePicker()

    // Clean up an orphaned voice recording when the surface unmounts
    // (back nav, ship switch, conversation switch). The DM screen
    // used to do this manually; the composer owns it now.
    DisposableEffect(whom) {
        onDispose {
            state.pendingVoice?.let { java.io.File(it.path).delete() }
            state.pendingVoice = null
        }
    }

    // Pick → validate → STAGE. The upload + send doesn't happen here
    // anymore: the picked image is held in state.pendingAttachment and
    // shown as a preview so the user can confirm they grabbed the right
    // one before it goes out. [sendAttachment] finishes the job.
    //
    // The ONLY thing rejected is an empty read — a cloud photo that never
    // downloaded, which would upload a blank object. We deliberately do NOT
    // gate on decodeImageDimensions: that's a layout hint, not a validity
    // oracle (desktop's ImageIO can't read WebP, which the picker offers and
    // Coil renders fine), and the preview is the user's own check now.
    val stage: (ByteArray, String, String, Boolean) -> Unit = { bytes, mime, name, isImage ->
        if (bytes.isEmpty()) {
            state.sendError = EMPTY_ATTACHMENT_ERROR
        } else {
            state.sendError = null
            state.pendingAttachment = PendingAttachment(bytes, mime, name, isImage)
        }
    }

    val onPickImage: () -> Unit = {
        scope.launch {
            val picked = runCatching { pickImage() }
                .onFailure { state.sendError = "couldn't read image: ${it.message ?: it::class.simpleName}" }
                .getOrNull() ?: return@launch
            stage(picked.bytes, picked.mimeType, picked.displayName, true)
        }
    }

    val onPickFile: () -> Unit = {
        scope.launch {
            val picked = runCatching { pickAnyFile() }
                .onFailure { state.sendError = "couldn't read file: ${it.message ?: it::class.simpleName}" }
                .getOrNull() ?: return@launch
            stage(
                picked.bytes, picked.mimeType, picked.displayName,
                picked.mimeType.startsWith("image/"),
            )
        }
    }

    // Confirm the staged attachment: upload + send. On failure we keep
    // the attachment staged (error shows above) so the user can retry
    // the send instead of re-picking.
    val sendAttachment: () -> Unit = {
        val pending = state.pendingAttachment
        if (pending != null) {
            state.uploading = true
            state.sendError = null
            scope.launch {
                runCatching {
                    val hostedUrl = repo.uploadImage(pending.bytes, pending.mimeType, pending.displayName)
                    if (pending.isImage) {
                        val dims = decodeImageDimensions(pending.bytes)
                        strategy.sendImage(
                            src = hostedUrl,
                            width = dims?.first ?: 0,
                            height = dims?.second ?: 0,
                            alt = pending.displayName,
                        )
                    } else {
                        strategy.sendText(hostedUrl)
                    }
                    // Sent — clear the stage and the orphaned text draft so
                    // the conversation list stops advertising "Draft:".
                    state.pendingAttachment = null
                    state.draft = TextFieldValue("")
                    drafts.clear(whom)
                }.onFailure { err ->
                    val kind = if (pending.isImage) "image" else "file"
                    state.sendError = "$kind failed: ${err.message ?: err::class.simpleName}"
                }
                state.uploading = false
            }
        }
    }

    // Common upload path used by drag-drop, clipboard paste, and the
    // existing image / file picker buttons. Image-shaped MIME goes
    // through strategy.sendImage so the recipient gets a structured
    // image post (where the surface supports it); everything else
    // posts the bare URL the way the file button does. Clears the
    // draft afterwards for the same reason the picker buttons do —
    // the user finalized a send, the textual draft is orphaned.
    val uploadAndSend: (DroppedFile) -> Unit = { file ->
        scope.launch {
            if (file.bytes.isEmpty()) {
                // Same guard the staged paths get — a multi-file drop was the
                // one path that could still upload a blank object and post an
                // empty message.
                state.sendError = EMPTY_ATTACHMENT_ERROR
                return@launch
            }
            state.uploading = true
            state.sendError = null
            runCatching {
                val hostedUrl = repo.uploadImage(file.bytes, file.mimeType, file.name)
                if (file.isImage) {
                    val dims = decodeImageDimensions(file.bytes)
                    strategy.sendImage(
                        src = hostedUrl,
                        width = dims?.first ?: 0,
                        height = dims?.second ?: 0,
                        alt = file.name,
                    )
                } else {
                    strategy.sendText(hostedUrl)
                }
                state.draft = TextFieldValue("")
                drafts.clear(whom)
            }.onFailure { err ->
                state.sendError = "upload failed: ${err.message ?: err::class.simpleName}"
            }
            state.uploading = false
        }
    }

    // Stage a pasted / single-dropped file into the same review-before-send
    // preview the picker buttons use, so a paste doesn't fire off before the
    // user can see what landed. Multi-file drops stay on [uploadAndSend]
    // (the preview holds one item).
    val stageDropped: (DroppedFile) -> Unit = { file ->
        stage(file.bytes, file.mimeType, file.name, file.isImage)
    }

    val updateDraft: (TextFieldValue) -> Unit = { next ->
        state.draft = next
        drafts.save(whom, next.text)
    }

    // Typing presence. Keying on the draft text means a keystroke
    // re-announces (the repo throttles to one poke per 15s against a
    // 30s server-side entry) and a pause simply lets it lapse — which
    // is the semantics we want. Emptying the composer, including the
    // clear every send path performs, retracts immediately: a timeout
    // never propagates to watchers, only an explicit clear does.
    LaunchedEffect(whom, state.draft.text) {
        if (state.draft.text.isBlank()) {
            repo.retractPresence(whom)
        } else {
            repo.announcePresence(whom)
        }
    }

    // An upload is the one thing worth announcing that the peer would
    // otherwise wait on with no explanation. %computing is exactly the
    // topic for it; the display text says which.
    LaunchedEffect(whom, state.uploading, state.pendingAttachment?.isImage) {
        if (state.uploading) {
            val what = if (state.pendingAttachment?.isImage != false) "an image" else "a file"
            repo.announcePresence(whom, Presence.TOPIC_COMPUTING, "uploading $what")
        } else {
            repo.retractPresence(whom, Presence.TOPIC_COMPUTING)
        }
    }

    // Belt-and-suspenders flush mirrored from the original DM body —
    // if the surface unmounts without onValueChange ever firing for
    // a clear, persist what we have so the conversation list and
    // the next mount agree.
    DisposableEffect(whom) {
        onDispose {
            drafts.save(whom, state.draft.text)
            // Leaving the screen mid-draft must not leave us announcing
            // forever on the peer's side.
            repo.retractPresenceNow(whom)
            repo.retractPresenceNow(whom, Presence.TOPIC_COMPUTING)
        }
    }

    val mention = detectMentionQuery(state.draft.text, state.draft.selection.start)
    val suggestions = remember(mention, allShips, contactMap) {
        mention?.let { (q, _) -> suggestionsFor(q, contactMap, allShips) } ?: emptyList()
    }
    val emojiQuery = detectEmojiQuery(state.draft.text, state.draft.selection.start)
    val emojiSuggestions = remember(emojiQuery) {
        emojiQuery?.let { (q, _) -> EmojiCatalog.search(q, limit = 6) } ?: emptyList()
    }
    // Arrow-key cursor into the emoji dropdown. Resets to the top each
    // time the query changes (new suggestion list); Tab/Enter completes
    // whichever row this points at.
    var emojiSel by remember(emojiQuery?.first) { mutableStateOf(0) }
    val slashTrigger = detectSlashTrigger(state.draft.text, state.draft.selection.start)
    val slashSuggestions = remember(slashTrigger) {
        slashTrigger?.let { filterSlashCommands(it.query) } ?: emptyList()
    }

    // Replace the active trigger token (`@x`, `:x`, `/x`) with a pick.
    // Shared by the dropdown clicks and the Tab key handler so both
    // insert identically. Each is only invoked while its trigger is live.
    fun replaceTrigger(startIdx: Int, inserted: String) {
        val caret = state.draft.selection.start
        val before = state.draft.text.substring(0, startIdx)
        val after = state.draft.text.substring(caret)
        updateDraft(
            TextFieldValue(
                text = before + inserted + after,
                selection = TextRange(before.length + inserted.length),
            ),
        )
    }
    val applyEmojiPick: (EmojiCatalog.Entry) -> Unit = { e -> replaceTrigger(emojiQuery!!.second, "${e.glyph} ") }
    val applyMentionPick: (String) -> Unit = { ship -> replaceTrigger(mention!!.second, "$ship ") }
    val applySlashPick: (SlashCommandSpec) -> Unit = { spec ->
        updateDraft(TextFieldValue("/${spec.name} ", TextRange(spec.name.length + 2)))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Disabled while a staged preview (attachment or voice) has
            // replaced the composer via early-return: the drop target sits on
            // this outer Column, so without the guard a drop would land
            // "behind" the preview — sending a stray file and flickering the
            // preview's Send/Discard buttons. (Fixes the same pre-existing
            // hole for the voice preview.)
            .fileDropTarget(
                enabled = canSend && !state.uploading &&
                    state.pendingAttachment == null && state.pendingVoice == null,
            ) { files ->
                // A single drop stages into the preview like a paste; a
                // multi-file drop still sends immediately (one preview slot).
                if (files.size == 1) stageDropped(files.first())
                else files.forEach(uploadAndSend)
            },
    ) {
        if (state.sendError != null) {
            Text(
                state.sendError!!,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (slashSuggestions.isNotEmpty() && slashTrigger != null) {
            SlashPicker(
                suggestions = slashSuggestions,
                onPick = applySlashPick,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        if (emojiSuggestions.isNotEmpty() && emojiQuery != null) {
            EmojiPickerDropdown(
                suggestions = emojiSuggestions,
                onPick = applyEmojiPick,
                selectedIndex = emojiSel,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        if (suggestions.isNotEmpty() && mention != null) {
            MentionPicker(
                suggestions = suggestions,
                onPick = applyMentionPick,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        val doSend: () -> Boolean = doSend@{
            val body = state.draft.text.trim()
            val quote = state.pendingQuote
            // A bare quote (empty body) is allowed so a user can
            // "react" to a message by quoting it without typing.
            val canEmit = (body.isNotEmpty() || quote != null) && canSend
            if (!canEmit) return@doSend false
            // UI-dispatched commands intercept BEFORE runCommand.
            // The autocomplete surfaces /img /file /mic; without
            // this branch they'd be silently swallowed by
            // runCommand's `Handled` fallthrough. Quoted sends
            // bypass — a quote is structured, not text the command
            // runner is meant to interpret.
            val firstWord = body.lowercase().substringBefore(' ')
            val handledInUi = when {
                quote != null -> false
                firstWord == "/img" -> {
                    onPickImage(); true
                }
                firstWord == "/file" -> {
                    onPickFile(); true
                }
                firstWord == "/mic" -> {
                    if (onSlashMic != null) {
                        onSlashMic()
                    } else {
                        state.sendError =
                            "/mic: tap the mic button instead — slash trigger isn't wired here"
                    }
                    true
                }
                else -> false
            }
            state.draft = TextFieldValue("")
            drafts.clear(whom)
            state.sendError = null
            onBeforeLocalEcho()
            state.pendingQuote = null
            if (!handledInUi) scope.launch {
                runCatching {
                    val cmd = if (quote == null) {
                        runCommand(
                            rawText = body,
                            repo = repo,
                            http = http,
                            locationProvider = locationProvider,
                            powerFeaturesEnabled = powerFeaturesEnabled,
                            toast = { msg -> state.sendError = msg },
                        )
                    } else CommandResult.NotACommand
                    when (cmd) {
                        is CommandResult.Send -> strategy.sendText(cmd.body)
                        is CommandResult.Handled -> {}
                        is CommandResult.Error -> state.sendError = cmd.message
                        is CommandResult.NotACommand -> {
                            if (quote != null && strategy.supportsQuote) {
                                strategy.sendQuote(body, quote.whom, quote.id)
                            } else {
                                strategy.sendText(body)
                            }
                        }
                    }
                }.onFailure { err ->
                    Log.e("ChatComposer", "send failed", err)
                    state.sendError = "send failed: ${err.message ?: err::class.simpleName}"
                }
            }
            true
        }

        state.pendingQuote?.let { q ->
            QuotePreviewRow(
                target = q,
                contactMap = contactMap,
                onDismiss = { state.pendingQuote = null },
            )
        }

        // Send-button + composer accent. App.kt drives theme primary
        // with the user's chosen accent so this single value covers
        // every primary-tinted composer surface uniformly.
        val sendAccent = MaterialTheme.colorScheme.primary
        val pv = state.pendingVoice
        if (pv != null) {
            VoicePreviewRow(
                pending = pv,
                sending = state.uploading,
                voicePlayer = voicePlayer,
                sendAccent = sendAccent,
                onCancel = {
                    java.io.File(pv.path).delete()
                    state.pendingVoice = null
                },
                onSend = {
                    state.uploading = true
                    state.sendError = null
                    state.pendingVoice = null
                    state.draft = TextFieldValue("")
                    drafts.clear(whom)
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
                            strategy.sendText("[$label]($hostedUrl)")
                            file.delete()
                        }.onFailure { err ->
                            Log.e("ChatComposer", "voice send failed", err)
                            state.sendError =
                                "voice send failed: ${err.message ?: err::class.simpleName}"
                        }
                        state.uploading = false
                    }
                },
            )
            return
        }

        val pa = state.pendingAttachment
        if (pa != null) {
            AttachmentPreviewRow(
                pending = pa,
                sending = state.uploading,
                sendAccent = sendAccent,
                onCancel = { state.pendingAttachment = null },
                onSend = sendAttachment,
            )
            return
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
                    onClick = onPickImage,
                    enabled = canSend && !state.uploading,
                    modifier = Modifier.size(36.dp),
                ) {
                    if (state.uploading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            Icons.Filled.Image,
                            contentDescription = "Attach image",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                IconButton(
                    onClick = onPickFile,
                    enabled = canSend && !state.uploading,
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
                        canSend && !state.uploading,
                        { path, durationMs ->
                            state.pendingVoice = PendingVoice(path, durationMs)
                        },
                    )
                }
            }
            OutlinedTextField(
                value = state.draft,
                onValueChange = updateDraft,
                placeholder = { Text(placeholder) },
                enabled = canSend,
                textStyle = MaterialTheme.typography.bodyMedium,
                visualTransformation = EmojiVisualTransformation,
                modifier = Modifier
                    .weight(1f)
                    // Android paste-image route (no-op on desktop, which
                    // uses the Ctrl+V intercept below). Same upload+send
                    // path as drag-drop and the picker.
                    .imagePasteTarget(
                        enabled = canSend && !state.uploading,
                        onImage = stageDropped,
                    )
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) {
                            return@onPreviewKeyEvent false
                        }
                        // Up/Down move the highlighted row in the emoji
                        // dropdown (clamped at the ends). Only while the
                        // emoji picker is live, so arrows otherwise keep
                        // their normal caret behaviour.
                        if (emojiQuery != null && emojiSuggestions.isNotEmpty() &&
                            (e.key == Key.DirectionDown || e.key == Key.DirectionUp)
                        ) {
                            val last = emojiSuggestions.lastIndex
                            emojiSel = if (e.key == Key.DirectionDown) {
                                (emojiSel + 1).coerceAtMost(last)
                            } else {
                                (emojiSel - 1).coerceAtLeast(0)
                            }
                            return@onPreviewKeyEvent true
                        }
                        // Up arrow on an empty composer jumps straight into
                        // editing your most recently sent message (matches
                        // Slack/Discord). Gated on an empty draft so Up
                        // still moves the caret while you're typing, and on
                        // onEditLast being wired (channel chats only).
                        if (e.key == Key.DirectionUp && onEditLast != null &&
                            state.draft.text.isEmpty()
                        ) {
                            onEditLast()
                            return@onPreviewKeyEvent true
                        }
                        // Tab accepts the highlighted autocomplete
                        // suggestion (emoji / mention / slash), mirroring a
                        // dropdown click. Only consumes Tab while a picker
                        // is live; otherwise it falls through.
                        if (e.key == Key.Tab) {
                            when {
                                emojiQuery != null && emojiSuggestions.isNotEmpty() ->
                                    applyEmojiPick(emojiSuggestions[emojiSel.coerceIn(0, emojiSuggestions.lastIndex)])
                                mention != null && suggestions.isNotEmpty() ->
                                    applyMentionPick(suggestions.first().ship)
                                slashTrigger != null && slashSuggestions.isNotEmpty() ->
                                    applySlashPick(slashSuggestions.first())
                                else -> return@onPreviewKeyEvent false
                            }
                            return@onPreviewKeyEvent true
                        }
                        // Ctrl+V (or Cmd+V on macOS) — if the
                        // clipboard holds an image, intercept the
                        // paste, stage it into the review preview, and
                        // consume the event so the text field doesn't
                        // ALSO try to paste (which would insert garbage
                        // like the file path or nothing). Plain text
                        // paste falls through normally.
                        val pasteCombo = e.key == Key.V &&
                            (e.isCtrlPressed || e.isMetaPressed)
                        if (pasteCombo && canSend && !state.uploading) {
                            val img = readClipboardImageOrNull()
                            if (img != null) {
                                stageDropped(img)
                                return@onPreviewKeyEvent true
                            }
                        }
                        if (e.key != Key.Enter) return@onPreviewKeyEvent false
                        if (e.isShiftPressed) {
                            // OutlinedTextField on CMP Desktop doesn't
                            // insert a newline on Shift+Enter from a
                            // hardware keyboard — bake it in here so
                            // the composer behaves like every other
                            // chat client. Replaces the current
                            // selection with "\n" and parks the caret
                            // after it.
                            val cur = state.draft
                            val start = cur.selection.start
                            val end = cur.selection.end
                            val newText = cur.text.substring(0, start) +
                                "\n" +
                                cur.text.substring(end)
                            updateDraft(
                                cur.copy(
                                    text = newText,
                                    selection = TextRange(start + 1),
                                ),
                            )
                            return@onPreviewKeyEvent true
                        }
                        doSend()
                        true
                    },
            )
            IconButton(
                onClick = { doSend() },
                enabled = canSend && state.draft.text.isNotBlank(),
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    modifier = Modifier.size(22.dp),
                    tint = if (canSend && state.draft.text.isNotBlank()) sendAccent
                    else LocalContentColor.current,
                )
            }
        }
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

@Composable
private fun AttachmentPreviewRow(
    pending: PendingAttachment,
    sending: Boolean,
    sendAccent: Color,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (pending.isImage) {
            // Coil 3 renders a ByteArray model directly — no upload / temp
            // file needed to preview the picked photo.
            AsyncImage(
                model = pending.bytes,
                contentDescription = pending.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.AttachFile,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                pending.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                if (pending.isImage) "Image · tap send to post" else "File · tap send to post",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(
            onClick = onCancel,
            enabled = !sending,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Discard attachment",
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
                    contentDescription = "Send attachment",
                    modifier = Modifier.size(22.dp),
                    tint = sendAccent,
                )
            }
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
    sendAccent: Color,
) {
    val seconds = (pending.durationMs / 1000L).coerceAtLeast(1L)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
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
                    tint = if (!sending) sendAccent else LocalContentColor.current,
                )
            }
        }
    }
}
