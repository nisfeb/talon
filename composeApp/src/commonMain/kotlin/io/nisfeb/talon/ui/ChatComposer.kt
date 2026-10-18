package io.nisfeb.talon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import io.nisfeb.talon.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import io.nisfeb.talon.util.deleteFile
import io.nisfeb.talon.util.fileName
import io.nisfeb.talon.util.readFileBytes
import io.nisfeb.talon.util.rememberImagePicker
import kotlinx.coroutines.launch
import io.ktor.client.HttpClient
import io.nisfeb.talon.ui.icons.TalonIcons

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
    // Any.hashCode() is identity-based on both JVM and Kotlin/Native —
    // consistent with the reference-equals above, no System.identityHashCode.
    override fun hashCode(): Int = super.hashCode()
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
    // The caret after the words, where the writer left off: at the start,
    // the next keystroke landed in front of a restored draft.
    var draft by mutableStateOf(TextFieldValue(initialDraftText, TextRange(initialDraftText.length)))
    var pendingQuote by mutableStateOf<MessageEntity?>(null)
    var pendingVoice by mutableStateOf<PendingVoice?>(null)
    var pendingAttachment by mutableStateOf<PendingAttachment?>(null)
    private var shownError by mutableStateOf<String?>(null)

    /** The line over the composer: what went wrong, in words. Setting it clears [sendErrorDetails]. */
    var sendError: String?
        get() = shownError
        set(value) {
            shownError = value
            sendErrorDetails = null
            sendErrorCalm = false
        }

    /** The failure whole, behind "Copy error details"; null where the line says it all. */
    var sendErrorDetails by mutableStateOf<String?>(null)
        private set

    /** The line is a slow ship, not a refusal: drawn quietly, not in the error colour. */
    var sendErrorCalm by mutableStateOf(false)
        private set

    /** [problem] over the composer, its error whole behind Copy error details. */
    fun show(problem: io.nisfeb.talon.util.Problem) {
        shownError = problem.line
        sendErrorDetails = problem.details
        sendErrorCalm = problem.calm
    }

    fun failed(what: String, err: Throwable) {
        val slow = io.nisfeb.talon.util.isShipSlow(err)
        sendError = if (slow) {
            "Your ship is slow, so the ${noun(what)} didn't go through. Try again when it's back."
        } else if (err is io.nisfeb.talon.urbit.UploadFailed) {
            err.message
        } else {
            "$what failed: " + ((err as? io.nisfeb.talon.urbit.PokeNacked)?.let { "the ship refused it" }
                ?: io.nisfeb.talon.util.readableReason(err.message) ?: "something went wrong")
        }
        sendErrorDetails = io.nisfeb.talon.util.errorDetailsOf(err)
        sendErrorCalm = slow
    }

    private fun noun(what: String) = when (what) {
        "send" -> "message"
        "react" -> "reaction"
        "notify" -> "notification setting"
        "voice send" -> "voice message"
        "add contact" -> "new contact"
        "remove contact" -> "contact removal"
        else -> what
    }

    var uploading by mutableStateOf(false)

    /** An edit on its way to the ship: the composer holds it, words and all, until the ship answers. */
    var saving by mutableStateOf(false)

    /**
     * The post being edited, if any.
     *
     * Editing happens in the composer rather than a dialog: it is the
     * same act as writing the message was, with the same keyboard,
     * autocomplete and formatting. A modal over the conversation hid
     * the thing being edited and gave the text a second, lesser
     * editor.
     */
    var editing by mutableStateOf<EditTarget?>(null)
}

/** A message being edited in place. */
data class EditTarget(
    val postId: String,
    val originalSentMs: Long,
    val originalContentJson: String?,
    /** Composer text the edit displaced. Restored on save/cancel so
     *  entering edit mode never destroys a half-typed message. */
    val priorDraftText: String = "",
)

/**
 * Quotes waiting on a composer, by composer-surface key.
 *
 * The composer is rebuilt for each conversation, so a quote picked in
 * one chat was thrown away by walking to another and back, while the
 * half-typed message beside it survived in the draft store. Held for
 * the session, like a desktop draft, and put down with the ship:
 * two ships can name the same conversation.
 *
 * The key is the surface's own key, not bare whom: a main composer
 * (keyed by whom) and a thread composer (keyed by its thread draft
 * key) for the same conversation each get a slot. Sharing one, each
 * surface's effect took turns overwriting the other — a quote one
 * consumed came back when the other re-wrote its stale copy.
 */
object PendingQuotes {
    private val byWhom = mutableMapOf<String, MessageEntity>()

    fun get(whom: String): MessageEntity? = byWhom[whom]

    fun set(whom: String, quote: MessageEntity?) {
        if (quote == null) byWhom.remove(whom) else byWhom[whom] = quote
    }

    fun clear() = byWhom.clear()
}

/**
 * A file's post: its name, linked. The upload's URL no longer carries the
 * name (uploadName), so a bare URL would leave nobody knowing what it is.
 */
internal fun fileLink(name: String, url: String) = "[📎 ${name.replace('[', '(').replace(']', ')')}]($url)"

/**
 * Upload one dropped, pasted or multi-picked file and send it, on the repo's
 * own scope. Launched from the screen's scope, leaving the chat mid-upload
 * cancelled both the upload and the send and the file never went. The staged
 * single-attachment path already carries on the repo for the same reason.
 * The caller keeps the screen bookkeeping (the uploading flag, the error).
 */
internal suspend fun uploadDroppedFile(
    file: DroppedFile,
    repo: TlonChatRepo,
    strategy: ChatSendStrategy,
): Result<Unit> = repo.carry {
    io.nisfeb.talon.util.runSuspendCatching {
        val hostedUrl = repo.uploadImage(file.bytes, file.mimeType, file.name)
        if (file.isImage) {
            val dims = decodeImageDimensions(file.bytes)
            strategy.sendImage(src = hostedUrl, width = dims?.first ?: 0, height = dims?.second ?: 0, alt = file.name)
        } else {
            strategy.sendText(fileLink(file.name, hostedUrl))
        }
    }
}

/**
 * Every file of a multi-file drop, one after another, all on the repo's
 * scope. Only each file's own upload was carried: leaving the chat after
 * the first had started dropped the rest of the batch. [failed] hears of
 * each file that did not go, with a null error for one that came back
 * empty.
 */
internal suspend fun uploadDroppedFiles(
    files: List<DroppedFile>,
    repo: TlonChatRepo,
    strategy: ChatSendStrategy,
    failed: (DroppedFile, Throwable?) -> Unit,
) = repo.carry {
    for (file in files) {
        // The guard the staged paths have: a blank object, an empty post.
        if (file.bytes.isEmpty()) failed(file, null)
        else uploadDroppedFile(file, repo, strategy).onFailure { failed(file, it) }
    }
}

/**
 * Upload a recorded voice message and send it, on the repo's own scope.
 * On the screen's, leaving the chat mid-upload cancelled the send, and
 * the composer going then deleted the recording: the message was lost.
 * The recording is deleted once sent, and on a failure only when [keep]
 * says nobody is left to try it again.
 */
internal suspend fun sendVoice(
    voice: PendingVoice,
    repo: TlonChatRepo,
    strategy: ChatSendStrategy,
    keep: () -> Boolean,
): Result<Unit> = repo.carry {
    io.nisfeb.talon.util.runSuspendCatching {
        val hostedUrl = repo.uploadImage(
            bytes = readFileBytes(voice.path),
            contentType = "audio/mp4",
            fileName = fileName(voice.path),
        )
        val seconds = (voice.durationMs / 1000L).coerceAtLeast(1L)
        strategy.sendText("[🎙 Voice ${seconds}s]($hostedUrl)")
    }.also { if (it.isSuccess || !keep()) deleteFile(voice.path) }
}

/**
 * What Ctrl+V takes: the clipboard's text where it has any, which the
 * field then pastes, and its image only where it has none. An app that
 * copies both had its image staged and the words lost. [hasText] is asked
 * first, so an image is not decoded only to be put aside.
 */
internal fun ctrlVPaste(hasText: () -> Boolean, read: () -> DroppedFile?): ClipboardPaste =
    if (hasText()) ClipboardPaste.None else clipboardPaste(read)

/**
 * Re-keys on [whom] so switching conversations starts the composer
 * fresh. Loads any persisted draft text from [drafts], and the quote
 * that was waiting on this composer's surface.
 *
 * [quoteKey] is this surface's slot in [PendingQuotes]; it defaults
 * to [whom], which is the main composer's key. A thread composer
 * passes its thread draft key so it and the main composer of the
 * same conversation don't share one slot.
 */
@Composable
fun rememberComposerState(whom: String, drafts: DraftStore, quoteKey: String = whom): ComposerState =
    remember(whom) {
        ComposerState(drafts.load(whom)).also { it.pendingQuote = PendingQuotes.get(quoteKey) }
    }

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

    /** Send a structured image, with [caption] (text written with it)
     *  under it in the same message. Both surfaces carry a full story —
     *  DM/post via repo.sendImage, thread via repo.replyImage — so the
     *  image renders inline rather than as a link. */
    suspend fun sendImage(src: String, width: Int, height: Int, alt: String, caption: String = "")

    val supportsQuote: Boolean

    /** Only invoked when [supportsQuote] is true. */
    suspend fun sendQuote(body: String, quoteWhom: String, quoteId: String)

    /** [sendImage] with a quote leading it, in one message. Only invoked when [supportsQuote] is true. */
    suspend fun sendImageQuote(src: String, width: Int, height: Int, alt: String, caption: String, quoteWhom: String, quoteId: String): Unit =
        throw UnsupportedOperationException("this surface does not carry a quote")
}

@Composable
fun ChatComposer(
    state: ComposerState,
    db: AppDatabase,
    repo: TlonChatRepo,
    http: HttpClient,
    drafts: DraftStore,
    whom: String,
    /** This surface's slot in [PendingQuotes]; defaults to [whom],
     *  the main composer's key. A thread composer passes its thread
     *  draft key so the two surfaces of one conversation don't
     *  overwrite each other's staged quote. */
    quoteKey: String = whom,
    contactMap: ContactMap,
    /** All ships eligible for `@` autocomplete. Caller computes from
     *  rows + contacts so the picker can suggest people from this
     *  surface even before they've been added to contacts. */
    allShips: List<String>,
    canSend: Boolean,
    hideComposerButtons: Boolean,
    /** Take the cursor as soon as this composer appears. Off where a
     *  keyboard would pop up over the conversation; a thread opened by
     *  Reply turns it on regardless, since typing is why it opened. */
    focusOnOpen: Boolean = !hasSoftKeyboard,
    /** Commit an in-place edit, on the repo's scope; throws when it did
     *  not land. The composer keeps the edit until this returns. Default
     *  no-op so surfaces that don't support editing need not pass one. */
    onSaveEdit: suspend (EditTarget, String) -> Unit = { _, _ -> },
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
    /** Triggered when `/call` is sent. Null where calls are
     *  unsupported (isCallsSupported gates the wiring upstream). */
    onSlashCall: (() -> Unit)? = null,
    /** `/party`: show who is on this channel's line. Null when the
     *  chat has no line, which also hides the suggestion. */
    onSlashParty: (() -> Unit)? = null,
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
    // The cursor goes to the field whenever the next thing to do is
    // type: the composer opened with a keyboard in hand, an edit
    // began, a quote or an attachment was picked. Keyed on what began
    // rather than on the flag, so switching to a different edit fires
    // and ending one (-> null) does not bring the keyboard back.
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    // Fixed to the ship this composer was built under. The dispose-time
    // save below can run a frame after a ship switch, and must not land
    // in the incoming ship's drafts. See DraftStore.bound.
    val store = remember(whom) { drafts.bound() }
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(state, state.editing?.postId, state.pendingQuote?.id, state.pendingAttachment) {
        val typingNext = focusOnOpen || state.editing != null ||
            state.pendingQuote != null || state.pendingAttachment != null
        if (typingNext) runCatching { fieldFocus.requestFocus() }
    }
    val pickImage = rememberImagePicker()
    val pickAnyFile = io.nisfeb.talon.util.rememberAnyFilePicker()

    // Clean up an orphaned voice recording when the surface unmounts
    // (back nav, ship switch, conversation switch). The DM screen
    // used to do this manually; the composer owns it now. One on its
    // way is the send's to finish, file and all (see [sendVoice]).
    DisposableEffect(whom) {
        onDispose {
            state.pendingVoice?.let { if (!state.uploading) deleteFile(it.path) }
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

    // Guard AND set before the picker suspends: two taps both saw no
    // picker in flight and asked for two, which on iOS is two
    // presentations racing and one of them silently dropped.
    var picking by remember { mutableStateOf(false) }
    val onPickImage: () -> Unit = {
        scope.launch {
            if (picking) return@launch
            picking = true
            val picked = runCatching { pickImage() }
                .also { picking = false }
                .onFailure { state.show(io.nisfeb.talon.util.problemOf("Couldn't read the image", it)) }
                .getOrNull() ?: return@launch
            stage(picked.bytes, picked.mimeType, picked.displayName, true)
        }
    }

    val onPickFile: () -> Unit = {
        scope.launch {
            if (picking) return@launch
            picking = true
            val picked = runCatching { pickAnyFile() }
                .also { picking = false }
                .onFailure { state.show(io.nisfeb.talon.util.problemOf("Couldn't read the file", it)) }
                .getOrNull() ?: return@launch
            stage(
                picked.bytes, picked.mimeType, picked.displayName,
                picked.mimeType.startsWith("image/"),
            )
        }
    }

    // Confirm the staged attachment: upload + send, with whatever was
    // written in the box going in the same message. It used to go out
    // alone and the text was then cleared, so a message written before
    // attaching a screenshot was lost. On failure we keep the attachment
    // staged and the text as it was (error shows above) so the user can
    // retry the send instead of re-picking.
    val sendAttachment: () -> Unit = {
        val pending = state.pendingAttachment
        if (pending != null) {
            state.uploading = true
            state.sendError = null
            val caption = state.draft.text.trim()
            // A staged quote goes with them, as it does with text: left
            // staged, the next Enter sent the quoted post again on its own.
            val quote = state.pendingQuote
            val quoted = quote?.takeIf { strategy.supportsQuote }
            scope.launch {
                // Uploaded and sent on the repo's scope: leaving the chat
                // mid-upload cancelled both, and the photo never went. Only
                // the bookkeeping below belongs to the screen.
                val sent = repo.carry {
                    io.nisfeb.talon.util.runSuspendCatching {
                        val hostedUrl = repo.uploadImage(pending.bytes, pending.mimeType, pending.displayName)
                        if (pending.isImage) {
                            val dims = decodeImageDimensions(pending.bytes)
                            val w = dims?.first ?: 0
                            val h = dims?.second ?: 0
                            if (quoted != null) strategy.sendImageQuote(hostedUrl, w, h, pending.displayName, caption, quoted.whom, quoted.id)
                            else strategy.sendImage(src = hostedUrl, width = w, height = h, alt = pending.displayName, caption = caption)
                        } else {
                            val link = fileLink(pending.displayName, hostedUrl)
                            val text = if (caption.isEmpty()) link else "$caption\n\n$link"
                            if (quoted != null) strategy.sendQuote(text, quoted.whom, quoted.id) else strategy.sendText(text)
                        }
                        // Sent with the screen gone: its saved draft was what went.
                        if (store.load(whom).trim() == caption) store.clear(whom)
                    }
                }
                sent.onSuccess {
                    // Sent, the text with it: clear the stage and the draft
                    // so the conversation list stops advertising "Draft:".
                    // Only if the box still says what went: the box stays
                    // open during the upload, and a line typed meanwhile is
                    // not the one that was sent. The quote likewise, unless
                    // another was picked meanwhile; one a surface cannot
                    // carry is dropped, as a text send drops it.
                    if (quote != null && state.pendingQuote?.id == quote.id) state.pendingQuote = null
                    state.pendingAttachment = null
                    if (state.draft.text.trim() == caption) {
                        state.draft = TextFieldValue("")
                        store.clear(whom)
                    }
                }.onFailure { err ->
                    val kind = if (pending.isImage) "image" else "file"
                    state.failed(kind, err)
                }
                state.uploading = false
            }
        }
    }

    // Stage a pasted / single-dropped file into the same review-before-send
    // preview the picker buttons use, so a paste doesn't fire off before the
    // user can see what landed. Multi-file drops send at once, through
    // [uploadDroppedFiles] (the preview holds one item).
    val stageDropped: (DroppedFile) -> Unit = { file ->
        stage(file.bytes, file.mimeType, file.name, file.isImage)
    }

    // The clipboard's image staged, or said to be too large. True when
    // there was one either way, so the field does not paste the
    // clipboard's text in its place.
    val takePaste: (ClipboardPaste) -> Boolean = { found ->
        when (found) {
            is ClipboardPaste.Image -> { stageDropped(found.file); true }
            is ClipboardPaste.Problem -> { state.sendError = found.message; true }
            ClipboardPaste.None -> false
        }
    }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    // A mention reads in the box as it will once sent, by this device's
    // naming settings; a draft with no @p keeps the plain look (on iOS a
    // transformation can cost the field its paste menu).
    val namesVersion by ShipNames.generation.collectAsState()
    val hasShip = io.nisfeb.talon.urbit.PATP_REGEX.containsMatchIn(state.draft.text)
    val composerLook = remember(namesVersion, hasShip) {
        if (hasShip) mentionTransformation { ShipNames.resolve(it) } else EmojiVisualTransformation
    }

    val updateDraft: (TextFieldValue) -> Unit = { typed ->
        // A mention shows as a name over its @p: it goes, and is stepped
        // over, as one piece.
        val next = keepMentionsWhole(state.draft, typed)
        state.draft = next
        // An edit is not a draft: saving it would bring it back as a new
        // message. The draft it displaced stays saved underneath.
        if (state.editing == null) store.save(whom, next.text)
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
            // Walking away puts the keyboard down with the screen. Nothing
            // else ever cleared focus, so on a platform where the keyboard
            // follows the focused field it stayed up over the next screen.
            runCatching { focusManager.clearFocus(force = true) }
            // Leaving mid-edit abandons the edit and keeps the real draft.
            store.save(whom, state.editing?.priorDraftText ?: state.draft.text)
            // Leaving the screen mid-draft must not leave us announcing
            // forever on the peer's side.
            repo.retractPresenceNow(whom)
            repo.retractPresenceNow(whom, Presence.TOPIC_COMPUTING)
        }
    }

    // `/invite <group> [~ship]`: its arguments get their own pickers, and
    // the mention picker stands down while one of them is live.
    val inDm = whom.startsWith("~")
    val inviteArg = detectInviteArg(state.draft.text, state.draft.selection.start, inDm)
    val myGroups by remember(db) { db.groups().streamGroups() }.collectAsState(initial = emptyList())
    val groupSuggestions = remember(inviteArg, myGroups) {
        (inviteArg as? InviteArg.Group)?.let { matchGroups(it.query, myGroups) } ?: emptyList()
    }
    val inviteShipSuggestions = remember(inviteArg, allShips, contactMap) {
        (inviteArg as? InviteArg.Ship)?.let { suggestionsFor(it.query, contactMap, allShips) } ?: emptyList()
    }
    var inviteSel by remember(inviteArg?.query) { mutableStateOf(0) }
    // Keep the quote with its composer's surface, so leaving and
    // coming back finds it still attached, the way the draft text is.
    LaunchedEffect(quoteKey, state.pendingQuote?.id) { PendingQuotes.set(quoteKey, state.pendingQuote) }
    val mention = if (inviteArg != null) null else detectMentionQuery(state.draft.text, state.draft.selection.start)
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
    // Same arrow-key cursor for the mention dropdown; resets whenever
    // the typed query changes so it always starts at the best match.
    var mentionSel by remember(mention?.first) { mutableStateOf(0) }
    val slashTrigger = detectSlashTrigger(state.draft.text, state.draft.selection.start)
    // Only advertise commands this surface can actually run — /call,
    // /mic and /loc are wiring-dependent, and offering them where the
    // wiring is null just autocompletes into an error. Typed
    // invocations still hit the per-command error strings as a backstop.
    val slashSuggestions = remember(slashTrigger, onSlashCall, onSlashMic, onSlashParty, locationProvider) {
        slashTrigger?.let {
            filterSlashCommands(it.query).filter { s ->
                when (s.name) {
                    "call" -> onSlashCall != null
                    "mic" -> onSlashMic != null
                    "party" -> onSlashParty != null
                    "loc" -> locationProvider != null
                    else -> true
                }
            }
        } ?: emptyList()
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
    val applyInvitePick: (String) -> Unit = { picked -> inviteArg?.let { replaceTrigger(it.start, "$picked ") } }
    /** The highlighted /invite pick, group or ship, or null when neither picker is up. */
    fun invitePick(): String? = when {
        groupSuggestions.isNotEmpty() -> groupSuggestions[inviteSel.coerceIn(0, groupSuggestions.lastIndex)].flag
        inviteShipSuggestions.isNotEmpty() -> inviteShipSuggestions[inviteSel.coerceIn(0, inviteShipSuggestions.lastIndex)].ship
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Disabled while an attachment is staged (there is one slot) or
            // the voice preview has replaced the composer via early-return:
            // the drop target sits on this outer Column, so without the
            // guard a drop would land behind it and send a stray file.
            .fileDropTarget(
                enabled = canSend && !state.uploading &&
                    state.pendingAttachment == null && state.pendingVoice == null,
            ) { files ->
                // A single drop stages into the preview like a paste; a
                // multi-file drop still sends immediately (one preview slot).
                if (files.size == 1) {
                    stageDropped(files.first())
                } else {
                    // Sequential on purpose, in one coroutine, so
                    // `uploading` stays true until the LAST file lands.
                    // sendError is cleared once for the whole batch:
                    // clearing per-file erased file N's failure the moment
                    // file N+1 started, and a missing message read as sent.
                    scope.launch {
                        state.sendError = null
                        state.uploading = true
                        uploadDroppedFiles(files, repo, strategy) { _, err ->
                            if (err == null) state.sendError = EMPTY_ATTACHMENT_ERROR else state.failed("upload", err)
                        }
                        state.uploading = false
                    }
                }
            },
    ) {
        // Writes waiting for a slow ship: said once, quietly, with what
        // the last try said behind the button.
        val slow = repo.shipSlow.collectAsState(initial = null).value
        if (slow != null) {
            NoteLine(
                io.nisfeb.talon.util.shipSlowLine(slow.queued, slow.health),
                calm = true,
                details = slow.details,
            )
        }
        state.sendError?.let { err ->
            NoteLine(err, calm = state.sendErrorCalm, details = state.sendErrorDetails)
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
        if (groupSuggestions.isNotEmpty()) {
            GroupPicker(
                groups = groupSuggestions,
                onPick = { applyInvitePick(it.flag) },
                selectedIndex = inviteSel,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        if (inviteShipSuggestions.isNotEmpty()) {
            MentionPicker(
                suggestions = inviteShipSuggestions,
                onPick = applyInvitePick,
                selectedIndex = inviteSel,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        if (suggestions.isNotEmpty() && mention != null) {
            MentionPicker(
                suggestions = suggestions,
                onPick = applyMentionPick,
                selectedIndex = mentionSel,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        val doSend: () -> Boolean = doSend@{
            // Editing short-circuits everything below: no commands, no
            // quote, no attachments — an edit replaces the text of a
            // post that already exists and nothing else.
            val edit = state.editing
            if (edit != null) {
                val text = state.draft.text.trim()
                if (text.isEmpty() || !canSend || state.saving) return@doSend false
                // The edit stays in the box until the ship has it: ended at
                // once, a failed edit threw the changed words away.
                state.saving = true
                state.sendError = null
                scope.launch {
                    io.nisfeb.talon.util.runSuspendCatching { onSaveEdit(edit, text) }
                        .onSuccess {
                            if (state.editing == edit) {
                                state.editing = null
                                state.draft = TextFieldValue(edit.priorDraftText, TextRange(edit.priorDraftText.length))
                            }
                        }
                        .onFailure { state.failed("edit", it) }
                    state.saving = false
                }
                return@doSend true
            }
            // A staged attachment goes with whatever is written, as one
            // message, text or none, and with a staged quote.
            if (state.pendingAttachment != null) {
                if (!canSend || state.uploading) return@doSend false
                sendAttachment()
                return@doSend true
            }
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
                // A staged quote turns the send into a quoted message,
                // so /invite typed on top of one would go out as
                // literal text. Refuse it and name the way out.
                quote != null && firstWord == "/invite" -> {
                    state.sendError = "/invite can't go out with a quote staged — dismiss the quote or send it first"
                    return@doSend false
                }
                quote != null -> false
                firstWord == "/invite" -> {
                    val invite = parseInvite(
                        body, whom.takeIf { inDm }, myGroups,
                        known = allShips, nicknameOf = { s -> contactMap.nickname(s) },
                    )
                    when (val p = invite) {
                        is InviteParse.Problem -> {
                            state.sendError = p.message
                            return@doSend false
                        }
                        is InviteParse.Ok -> {
                            val title = myGroups.firstOrNull { it.flag == p.flag }?.title ?: p.flag
                            scope.launch {
                                state.sendError = runCatching { repo.inviteToGroup(p.flag, p.ship) }
                                    .fold({ "Invited ${shipHandle(p.ship)} to $title." }, ::inviteFailure)
                            }
                        }
                    }
                    true
                }
                firstWord == "/img" -> {
                    onPickImage(); true
                }
                firstWord == "/file" -> {
                    onPickFile(); true
                }
                firstWord == "/call" -> {
                    if (onSlashCall != null) {
                        onSlashCall()
                    } else {
                        state.sendError = "/call isn't available on this platform yet"
                    }
                    true
                }
                firstWord == "/mic" -> {
                    if (onSlashMic != null) {
                        onSlashMic()
                    } else {
                        state.sendError =
                            "/mic: ${io.nisfeb.talon.ui.tapWord.lowercase()} the mic button instead."
                    }
                    true
                }
                firstWord == "/party" -> {
                    if (onSlashParty != null) {
                        onSlashParty()
                    } else {
                        state.sendError = "/party: this chat has no party line"
                    }
                    true
                }
                else -> false
            }
            state.draft = TextFieldValue("")
            store.clear(whom)
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
                        is CommandResult.Error -> {
                            state.show(io.nisfeb.talon.util.Problem(cmd.message, cmd.details))
                            // Give the typed command back — commands have
                            // no optimistic echo, so the cleared draft
                            // meant retyping a long /poll to fix one
                            // character. Skip if they've started typing
                            // something new meanwhile.
                            if (state.draft.text.isEmpty()) {
                                state.draft = TextFieldValue(body, TextRange(body.length))
                            }
                        }
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
                    state.failed("send", err)
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

        // An attachment sits above the box, which stays open: what is
        // written can be read and changed before the two go together.
        // It used to take the box's place, and its Send posted the file
        // alone and threw the text away.
        state.pendingAttachment?.let { pa ->
            AttachmentStrip(
                pending = pa,
                sending = state.uploading,
                onCancel = { state.pendingAttachment = null },
            )
        }

        // Says what the composer is about to do, and offers the way
        // out. Without it an edit in progress is indistinguishable
        // from a half-typed message, and the only visible difference
        // would be the send icon.
        if (state.editing != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (state.saving) "Saving…" else "Editing message",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    val prior = state.editing?.priorDraftText.orEmpty()
                    state.editing = null
                    state.draft = TextFieldValue(prior, TextRange(prior.length))
                }) { Text("Cancel") }
            }
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
                    deleteFile(pv.path)
                    state.pendingVoice = null
                },
                onSend = {
                    state.uploading = true
                    state.sendError = null
                    scope.launch {
                        // On the repo's scope, as an attachment is. On a
                        // failure the recording stays staged so it can be
                        // sent again. The words in the box are not part of
                        // it and stay where they are.
                        sendVoice(pv, repo, strategy, keep = { state.pendingVoice == pv })
                            .onSuccess { if (state.pendingVoice == pv) state.pendingVoice = null }
                            .onFailure { err ->
                                Log.e("ChatComposer", "voice send failed", err)
                                state.failed("voice send", err)
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
            // Shown even with the composer buttons hidden: it only
            // exists while the clipboard holds an image, so it is never
            // a dead button, and hidden buttons are how iOS users lose
            // their only way to paste one.
            // Paste-image action for platforms whose text field
            // can't receive one (iOS; desktop too, beside Ctrl+V). Only shown when the
            // clipboard actually holds an image, so it doesn't sit
            // there as a dead button — and the check is the cheap,
            // notification-free kind.
            if (needsManualImagePaste && canSend && !state.uploading) {
                // Polled, not read straight into composition. The
                // pasteboard is not snapshot state, so a plain read
                // is sampled once and never re-evaluated: you copy
                // an image in another app, come back, and the
                // button is still absent because nothing told
                // Compose to look again. Nothing else in the frame
                // changes when the clipboard does.
                //
                // hasImages inspects declared types only, so this
                // costs nothing and never trips the iOS "pasted
                // from" banner.
                var hasImage by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    while (true) {
                        hasImage = clipboardHasImage()
                        kotlinx.coroutines.delay(600)
                    }
                }
                if (hasImage) {
                    io.nisfeb.talon.ui.IconButton(tip = "Paste image", onClick = {
                            takePaste(clipboardPaste())
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            TalonIcons.ContentPaste,
                            contentDescription = "Paste image",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            if (!hideComposerButtons) {
                io.nisfeb.talon.ui.IconButton(tip = "Attach image", onClick = onPickImage,
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
                            TalonIcons.Image,
                            contentDescription = "Attach image",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                io.nisfeb.talon.ui.IconButton(tip = "Attach file", onClick = onPickFile,
                    enabled = canSend && !state.uploading,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        TalonIcons.AttachFile,
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
                visualTransformation = composerLook,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(fieldFocus)
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
                        // While an input method composes (pinyin, kana,
                        // hangul), the keys are its own: Enter confirms a
                        // candidate, arrows and Tab move through them.
                        // Taken here, Enter sent the half-typed message.
                        if (state.draft.composition != null) return@onPreviewKeyEvent false
                        // Escape drops a staged attachment, as its cross does.
                        if (e.key == Key.Escape && state.pendingAttachment != null && !state.uploading) {
                            state.pendingAttachment = null
                            return@onPreviewKeyEvent true
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
                        // And the /invite argument pickers.
                        val invitePicks = groupSuggestions.size.takeIf { it > 0 } ?: inviteShipSuggestions.size
                        if (invitePicks > 0 && (e.key == Key.DirectionDown || e.key == Key.DirectionUp)) {
                            inviteSel = if (e.key == Key.DirectionDown) (inviteSel + 1).coerceAtMost(invitePicks - 1)
                            else (inviteSel - 1).coerceAtLeast(0)
                            return@onPreviewKeyEvent true
                        }
                        // Same for the mention dropdown.
                        if (mention != null && suggestions.isNotEmpty() &&
                            (e.key == Key.DirectionDown || e.key == Key.DirectionUp)
                        ) {
                            val last = suggestions.lastIndex
                            mentionSel = if (e.key == Key.DirectionDown) {
                                (mentionSel + 1).coerceAtMost(last)
                            } else {
                                (mentionSel - 1).coerceAtLeast(0)
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
                                invitePick() != null -> applyInvitePick(invitePick()!!)
                                emojiQuery != null && emojiSuggestions.isNotEmpty() ->
                                    applyEmojiPick(emojiSuggestions[emojiSel.coerceIn(0, emojiSuggestions.lastIndex)])
                                mention != null && suggestions.isNotEmpty() ->
                                    applyMentionPick(
                                        suggestions[
                                            mentionSel.coerceIn(0, suggestions.lastIndex),
                                        ].ship,
                                    )
                                slashTrigger != null && slashSuggestions.isNotEmpty() ->
                                    applySlashPick(slashSuggestions.first())
                                else -> return@onPreviewKeyEvent false
                            }
                            return@onPreviewKeyEvent true
                        }
                        // Ctrl+V (or Cmd+V on macOS) — if the
                        // clipboard holds an image and no text, intercept
                        // the paste, stage it into the review preview, and
                        // consume the event so the text field doesn't
                        // ALSO try to paste (which would insert garbage
                        // like the file path or nothing). Text pastes
                        // normally, even beside an image (see [ctrlVPaste]);
                        // the Paste image button takes that one.
                        val pasteCombo = e.key == Key.V &&
                            (e.isCtrlPressed || e.isMetaPressed)
                        if (pasteCombo && canSend && !state.uploading &&
                            takePaste(ctrlVPaste(clipboard::hasText, ::readClipboardImageOrNull))
                        ) {
                            return@onPreviewKeyEvent true
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
                        // Enter with a live emoji / mention picker accepts
                        // the highlighted item, exactly like Tab — sending
                        // mid-completion put ":smi" or a half-typed name
                        // into the room. Accepting closes the picker (the
                        // query no longer parses), so the NEXT Enter sends:
                        // pick-then-send is a double tap of the same key.
                        // Enter on an /invite argument picks it, like Tab; the next Enter sends.
                        invitePick()?.let {
                            applyInvitePick(it)
                            return@onPreviewKeyEvent true
                        }
                        if (emojiQuery != null && emojiSuggestions.isNotEmpty()) {
                            applyEmojiPick(
                                emojiSuggestions[emojiSel.coerceIn(0, emojiSuggestions.lastIndex)],
                            )
                            return@onPreviewKeyEvent true
                        }
                        if (mention != null && suggestions.isNotEmpty()) {
                            applyMentionPick(
                                suggestions[mentionSel.coerceIn(0, suggestions.lastIndex)].ship,
                            )
                            return@onPreviewKeyEvent true
                        }
                        // Slash too, same double-Enter rhythm: the pick's
                        // trailing space ends the trigger (whitespace kills
                        // detectSlashTrigger), so the popup closes and the
                        // second Enter runs the completed command.
                        if (slashTrigger != null && slashSuggestions.isNotEmpty()) {
                            applySlashPick(slashSuggestions.first())
                            return@onPreviewKeyEvent true
                        }
                        doSend()
                        true
                    },
            )
            val isEditing = state.editing != null
            // A staged quote with no text is sendable (doSend allows the
            // bare-quote "react" gesture) — the button must agree, or on
            // touch, where hardware Enter doesn't exist, a bare quote is
            // unreachable.
            val sendable = canSend && !state.saving &&
                (state.draft.text.isNotBlank() || state.pendingQuote != null ||
                    (state.pendingAttachment != null && !state.uploading))
            io.nisfeb.talon.ui.IconButton(tip = if (isEditing) "Save edit" else "Send", onClick = { doSend() },
                enabled = sendable,
                modifier = Modifier.size(36.dp),
            ) {
                // A tick, not a paper plane. Editing and sending are
                // different acts and the button is the only thing that
                // says which one is about to happen.
                Icon(
                    if (isEditing) Icons.Filled.Check else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (isEditing) "Save edit" else "Send",
                    modifier = Modifier.size(22.dp),
                    tint = if (sendable) sendAccent
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
        io.nisfeb.talon.ui.IconButton(tip = "Cancel quote", onClick = onDismiss, modifier = if (isTouchPrimary) Modifier else Modifier.size(28.dp)) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Cancel quote",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
internal fun AttachmentStrip(
    pending: PendingAttachment,
    sending: Boolean,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
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
                filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
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
                    TalonIcons.AttachFile,
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
                if (sending) "Sending" else (if (pending.isImage) "Image" else "File") + ", sent with your message",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (sending) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        } else {
            io.nisfeb.talon.ui.IconButton(tip = "Discard attachment", onClick = onCancel, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Discard attachment",
                    modifier = Modifier.size(22.dp),
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
        else "🎙 ${seconds}s recorded — preview not available, ${io.nisfeb.talon.ui.tapWord.lowercase()} send when ready"
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        io.nisfeb.talon.ui.IconButton(tip = "Discard recording", onClick = onCancel,
            enabled = !sending,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Discard recording",
                modifier = Modifier.size(22.dp),
            )
        }
        io.nisfeb.talon.ui.IconButton(tip = "Send recording", onClick = onSend,
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

