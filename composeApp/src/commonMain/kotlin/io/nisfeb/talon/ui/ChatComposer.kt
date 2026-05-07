package io.nisfeb.talon.ui

import androidx.compose.foundation.background
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.urbit.StoryCache
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

/** A staged voice recording awaiting send confirmation. */
data class PendingVoice(val path: String, val durationMs: Long)

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

    /** Send a structured image post when the surface supports it.
     *  Threads don't have replyImage; their impl falls back to
     *  embedding the URL as markdown via [sendText]. */
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

    val onPickAndSendImage: () -> Unit = {
        scope.launch {
            val picked = pickImage() ?: return@launch
            state.uploading = true
            state.sendError = null
            runCatching {
                val dims = decodeImageDimensions(picked.bytes)
                val hostedUrl = repo.uploadImage(picked.bytes, picked.mimeType, picked.displayName)
                strategy.sendImage(
                    src = hostedUrl,
                    width = dims?.first ?: 0,
                    height = dims?.second ?: 0,
                    alt = picked.displayName,
                )
                // Image-attach is a send action even though the user
                // may have typed unrelated text first — clear so the
                // conversation list doesn't keep advertising "Draft:".
                state.draft = TextFieldValue("")
                drafts.clear(whom)
            }.onFailure { err ->
                state.sendError = "image failed: ${err.message ?: err::class.simpleName}"
            }
            state.uploading = false
        }
    }

    val onPickAndSendFile: () -> Unit = {
        scope.launch {
            val picked = pickAnyFile() ?: return@launch
            state.uploading = true
            state.sendError = null
            runCatching {
                val hostedUrl = repo.uploadImage(picked.bytes, picked.mimeType, picked.displayName)
                strategy.sendText(hostedUrl)
                state.draft = TextFieldValue("")
                drafts.clear(whom)
            }.onFailure { err ->
                state.sendError = "file failed: ${err.message ?: err::class.simpleName}"
            }
            state.uploading = false
        }
    }

    val updateDraft: (TextFieldValue) -> Unit = { next ->
        state.draft = next
        drafts.save(whom, next.text)
    }
    // Belt-and-suspenders flush mirrored from the original DM body —
    // if the surface unmounts without onValueChange ever firing for
    // a clear, persist what we have so the conversation list and
    // the next mount agree.
    DisposableEffect(whom) {
        onDispose { drafts.save(whom, state.draft.text) }
    }

    val mention = detectMentionQuery(state.draft.text, state.draft.selection.start)
    val suggestions = remember(mention, allShips, contactMap) {
        mention?.let { (q, _) -> suggestionsFor(q, contactMap, allShips) } ?: emptyList()
    }
    val emojiQuery = detectEmojiQuery(state.draft.text, state.draft.selection.start)
    val emojiSuggestions = remember(emojiQuery) {
        emojiQuery?.let { (q, _) -> EmojiCatalog.search(q, limit = 6) } ?: emptyList()
    }
    val slashTrigger = detectSlashTrigger(state.draft.text, state.draft.selection.start)
    val slashSuggestions = remember(slashTrigger) {
        slashTrigger?.let { filterSlashCommands(it.query) } ?: emptyList()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
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
                onPick = { spec ->
                    val newText = "/${spec.name} "
                    updateDraft(
                        TextFieldValue(
                            text = newText,
                            selection = TextRange(newText.length),
                        ),
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
                    val caret = state.draft.selection.start
                    val before = state.draft.text.substring(0, colonIdx)
                    val after = state.draft.text.substring(caret)
                    val inserted = "${entry.glyph} "
                    val newText = before + inserted + after
                    val newCaret = before.length + inserted.length
                    updateDraft(
                        TextFieldValue(
                            text = newText,
                            selection = TextRange(newCaret),
                        ),
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
                    val caret = state.draft.selection.start
                    val before = state.draft.text.substring(0, atIdx)
                    val after = state.draft.text.substring(caret)
                    val inserted = "$ship "
                    val newText = before + inserted + after
                    val newCaret = before.length + inserted.length
                    updateDraft(
                        TextFieldValue(
                            text = newText,
                            selection = TextRange(newCaret),
                        ),
                    )
                },
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
                    onPickAndSendImage(); true
                }
                firstWord == "/file" -> {
                    onPickAndSendFile(); true
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
                    onClick = onPickAndSendFile,
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
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown || e.key != Key.Enter) {
                            return@onPreviewKeyEvent false
                        }
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
