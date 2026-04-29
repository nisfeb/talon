// TEMPORARY DUPLICATE of app/src/main/java/io/nisfeb/talon/ui/StoryRenderer.kt
// Diverges from production in controlled ways so it compiles in commonMain:
//  1. InlineCitation no longer pulls TalonApplication via LocalContext.
//     A new LocalCiteResolver CompositionLocal handles cite lookup so any
//     consumer (DmChatScreen) wires its own resolver.
//  2. LocWidgetBlock and CalWidgetBlock's Android Intent launches are
//     stubbed: TODO(port-d5-followup) — desktop will need its own URL
//     opener.
// Keep in sync with production until app/ is removed in Stage F.
package io.nisfeb.talon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.urbit.StoryCache
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.nisfeb.talon.urbit.MENTION_TAG
import io.nisfeb.talon.urbit.StoryPart
import io.nisfeb.talon.urbit.URL_TAG
import kotlinx.coroutines.sync.withLock

/**
 * Resolver for citation lookups. The production app couples this
 * directly to TalonApplication; commonMain takes it via a
 * CompositionLocal so each entry point provides its own implementation.
 *
 * `findLocal` should hit the local DB; `fetchPost`/`fetchReply` scry
 * the channel for cites the local window doesn't already contain.
 */
interface CiteResolver {
    suspend fun findLocal(whom: String, da: String): MessageEntity?
    suspend fun fetchPost(whom: String, da: String): MessageEntity?
    suspend fun fetchReply(whom: String, postDa: String, replyDa: String): MessageEntity?
}

val LocalCiteResolver = compositionLocalOf<CiteResolver?> { null }

/**
 * Process-level cache + per-key dedup for citation resolution.
 * Without this, N visible citations to the same channel/post each
 * fire their own scry — a dense chat with 8 cites = 8 redundant
 * network round-trips, and scrolling re-creates the Composables
 * so cites re-resolve every time they re-enter the viewport.
 *
 * Caches positives only; null results are retried (a scry that
 * failed once might succeed later when network improves). The
 * underlying message body is effectively immutable for cite
 * purposes (edits / deletes show in separate UI), so unbounded
 * cache growth is fine — bounded by unique (whom, postDa) pairs
 * the user has viewed in a session, GC'd on process exit.
 *
 * The per-key Mutex serializes concurrent loads for the same key
 * so simultaneous renders coalesce into one network call.
 */
internal object CiteCache {
    private val cache = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, MessageEntity>()
    private val mutexes = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, kotlinx.coroutines.sync.Mutex>()

    suspend fun resolve(
        whom: String,
        da: String,
        load: suspend () -> MessageEntity?,
    ): MessageEntity? {
        val key = whom to da
        cache[key]?.let { return it }
        val mutex = mutexes.getOrPut(key) { kotlinx.coroutines.sync.Mutex() }
        return mutex.withLock {
            cache[key]?.let { return@withLock it }
            val result = load()
            if (result != null) cache[key] = result
            mutexes.remove(key)
            result
        }
    }
}

enum class MediaKind { AUDIO, VIDEO }

fun classifyMediaUrl(url: String): MediaKind? {
    val lower = url.lowercase().substringBefore('?').substringBefore('#')
    return when {
        lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".aac") ||
            lower.endsWith(".wav") || lower.endsWith(".ogg") || lower.endsWith(".flac") ->
            MediaKind.AUDIO
        lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mov") ||
            lower.endsWith(".m4v") -> MediaKind.VIDEO
        else -> null
    }
}

/** All (url, kind) media references in a parsed story, in order. */
fun mediaInStory(parts: List<StoryPart>): List<Pair<String, MediaKind>> {
    val out = mutableListOf<Pair<String, MediaKind>>()
    val seen = mutableSetOf<String>()
    for (p in parts) {
        if (p !is StoryPart.Text) continue
        val anns = p.text.getStringAnnotations(URL_TAG, 0, p.text.length)
        for (a in anns) {
            if (a.item in seen) continue
            val kind = classifyMediaUrl(a.item) ?: continue
            out += a.item to kind
            seen += a.item
        }
    }
    return out
}

@Composable
fun StoryRenderer(
    parts: List<StoryPart>,
    modifier: Modifier = Modifier,
    onMentionTap: (String) -> Unit = {},
    onLinkTap: (String) -> Unit = {},
    onImageTap: (String) -> Unit = {},
    onCitationTap: (target: String) -> Unit = {},
    /** Long-press anywhere inside the story — including on annotated
     * text — should bubble up here so the parent row's action sheet
     * stays reachable. */
    onLongPress: (() -> Unit)? = null,
    /** Reactions on this message, used by the poll widget to render
     *  per-option tallies. */
    reactions: List<io.nisfeb.talon.data.ReactionEntity> = emptyList(),
    /** Viewer's own patp — lets the poll widget highlight the row the
     *  viewer already voted for. */
    ourPatp: String? = null,
    /** Tap-an-option callback: the renderer invokes this with the
     *  keycap emoji the user tapped. Host is expected to do the same
     *  toggle-on-same / replace-on-different logic used by the normal
     *  reaction pills. */
    onPollVote: ((emoji: String) -> Unit)? = null,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        parts.forEach { part ->
            when (part) {
                is StoryPart.Text -> {
                    val hasSpans = part.text.spanStyles.isNotEmpty() ||
                        part.text.paragraphStyles.isNotEmpty()
                    val hasAnnotations = part.text
                        .getStringAnnotations(0, part.text.length)
                        .isNotEmpty()

                    if (!hasSpans && !hasAnnotations) {
                        Text(
                            part.text.text,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
                        Text(
                            text = part.text,
                            style = MaterialTheme.typography.bodyMedium,
                            onTextLayout = { layout.value = it },
                            modifier = if (hasAnnotations) {
                                Modifier.pointerInput(part.text, onLongPress) {
                                    detectTapGestures(
                                        onLongPress = { onLongPress?.invoke() },
                                        onTap = { pos ->
                                            val l = layout.value ?: return@detectTapGestures
                                            val offset = l.getOffsetForPosition(pos)
                                            val ann = part.text
                                                .getStringAnnotations(offset, offset)
                                                .firstOrNull() ?: return@detectTapGestures
                                            when (ann.tag) {
                                                URL_TAG -> onLinkTap(ann.item)
                                                MENTION_TAG -> onMentionTap(ann.item)
                                            }
                                        },
                                    )
                                }
                            } else Modifier,
                        )
                    }
                }

                is StoryPart.Image -> {
                    val aspect = if (part.width != null && part.height != null && part.height > 0) {
                        part.width.toFloat() / part.height.toFloat()
                    } else null
                    AsyncImage(
                        model = part.src,
                        contentDescription = part.alt,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .widthIn(max = 320.dp)
                            .heightIn(max = 360.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onImageTap(part.src) }
                            .let { if (aspect != null) it.fillMaxWidth() else it },
                    )
                }

                is StoryPart.Code -> Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        part.code,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }

                is StoryPart.LinkPreview -> InlineLinkPreview(preview = part) {
                    onLinkTap(part.url)
                }

                is StoryPart.Citation -> InlineCitation(cite = part) {
                    part.openTarget?.let(onCitationTap)
                }

                is StoryPart.TzWidget -> TzWidgetBlock(
                    instantEpochMs = part.instantEpochMs,
                    sourceLabel = part.sourceLabel,
                )

                is StoryPart.CalWidget -> CalWidgetBlock(
                    startEpochMs = part.startEpochMs,
                    endEpochMs = part.endEpochMs,
                    title = part.title,
                )

                is StoryPart.PollWidget -> PollWidgetBlock(
                    question = part.question,
                    options = part.options,
                    reactions = reactions,
                    ourPatp = ourPatp,
                    onVote = onPollVote,
                )

                is StoryPart.LocWidget -> LocWidgetBlock(
                    lat = part.lat,
                    lng = part.lng,
                )
            }
        }

        // Inline media players below the text content, one per linked
        // audio/video URL (de-duped). Lets senders just paste a URL and
        // get playback without needing a bespoke block type.
        mediaInStory(parts).forEach { (url, kind) ->
            when (kind) {
                MediaKind.AUDIO -> InlineAudioPlayer(url)
                MediaKind.VIDEO -> InlineVideoPlayer(url)
            }
        }
    }
}

// TODO(port-d5-followup): port InlineAudioPlayer/InlineVideoPlayer
// (currently in app/ui/MediaPlayerInline.kt) using AndroidView+ExoPlayer
// on Android and a desktop equivalent (JavaFX MediaPlayer or VLCJ).
// For now both stubs render as a tappable URL pill so users still see
// the link.
@Composable
private fun InlineAudioPlayer(url: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "🔊 $url",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun InlineVideoPlayer(url: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "🎬 $url",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun InlineLinkPreview(
    preview: StoryPart.LinkPreview,
    onTap: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 420.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onTap)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (!preview.imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = preview.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .widthIn(max = 96.dp)
                    .heightIn(min = 72.dp, max = 72.dp)
                    .clip(RoundedCornerShape(6.dp)),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val caption = preview.siteName
                ?: runCatching { java.net.URI(preview.url).host?.removePrefix("www.") }
                    .getOrNull()
                ?: preview.url
            Text(
                caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!preview.title.isNullOrBlank()) {
                Text(
                    preview.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                )
            }
            if (!preview.description.isNullOrBlank()) {
                Text(
                    preview.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun InlineCitation(
    cite: StoryPart.Citation,
    onTap: () -> Unit,
) {
    val tappable = cite.openTarget != null
    val resolver = LocalCiteResolver.current

    // Resolve cite → stored message, falling back to a channel scry
    // when the post isn't in our local window. Routes through
    // CiteCache so 50 cites in viewport for the same channel/post
    // coalesce into one network call instead of stampeding the
    // ship with 50.
    var resolved by remember(cite) { mutableStateOf<MessageEntity?>(null) }
    LaunchedEffect(cite, resolver) {
        if (resolver == null) return@LaunchedEffect
        val whom = cite.openTarget ?: return@LaunchedEffect
        val da = cite.replyDa ?: cite.postDa ?: return@LaunchedEffect
        resolved = CiteCache.resolve(whom, da) {
            resolver.findLocal(whom, da)?.let { return@resolve it }
            if (whom.startsWith("chat/")) {
                if (cite.replyDa != null && cite.postDa != null) {
                    resolver.fetchReply(whom, cite.postDa, cite.replyDa)
                } else {
                    resolver.fetchPost(whom, da)
                }
            } else null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 420.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .let { if (tappable) it.clickable(onClick = onTap) else it }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            cite.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val r = resolved
        if (r != null) {
            val body = remember(r.id, r.contentJson) {
                StoryCache.textFor(r.id, r.contentJson)
                    .replace('\n', ' ')
                    .take(240)
            }
            Text(
                r.author,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            if (body.isNotBlank()) {
                Text(body, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
            }
        } else {
            Text(
                "Referenced post",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            )
        }
    }
}

/**
 * Viewer-side rendering for the `[poll|…]` tag. Shows the question
 * and each option prefixed by its keycap-digit vote emoji. Voting is
 * handled by the normal reaction UI — users long-press the message
 * and react with the matching keycap emoji.
 */
@Composable
private fun PollWidgetBlock(
    question: String,
    options: List<String>,
    reactions: List<io.nisfeb.talon.data.ReactionEntity>,
    ourPatp: String?,
    onVote: ((emoji: String) -> Unit)?,
) {
    // Per-option tallies from the keycap-digit reactions. Any reaction
    // with a non-keycap emoji is ignored for vote counting but still
    // shows in the normal reaction bar.
    val tallies = remember(reactions, options.size) {
        IntArray(options.size).also { arr ->
            for (r in reactions) {
                val idx = VOTE_EMOJIS.indexOf(r.emoji)
                if (idx in 0 until options.size) arr[idx] += 1
            }
        }
    }
    val mineIndex = remember(reactions, ourPatp) {
        if (ourPatp == null) -1
        else VOTE_EMOJIS.indexOf(
            reactions.firstOrNull { it.author == ourPatp }?.emoji
        )
    }
    val totalVotes = tallies.sum()
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                "📊 $question",
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Text(
                if (totalVotes == 0) "Tap an option to vote."
                else "$totalVotes vote${if (totalVotes == 1) "" else "s"} · tap to change.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
            )
            for ((i, opt) in options.withIndex()) {
                val emoji = VOTE_EMOJIS.getOrNull(i) ?: "•"
                val count = tallies[i]
                val mine = i == mineIndex
                val rowMod = if (onVote != null) {
                    Modifier.fillMaxWidth().clickable { onVote(emoji) }
                } else Modifier.fillMaxWidth()
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (mine) MaterialTheme.colorScheme.primaryContainer
                    else androidx.compose.ui.graphics.Color.Transparent,
                    modifier = Modifier.padding(vertical = 2.dp),
                ) {
                    Row(
                        modifier = rowMod.padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            emoji,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.widthIn(min = 28.dp),
                        )
                        Text(
                            opt,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (mine) FontWeight.SemiBold
                                else FontWeight.Normal,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        if (count > 0) {
                            Text(
                                count.toString(),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Viewer-side rendering for the `[loc|lat|lng]` tag. Shows the
 * coordinates and an "Open map" action that hands off to the native
 * map app via a `geo:` URI, falling back to the OSM URL in a browser
 * if no map app is installed.
 */
@Composable
private fun LocWidgetBlock(
    lat: Double,
    lng: Double,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "📍 Location",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                Text(
                    "%.5f, %.5f".format(lat, lng),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.TextButton(onClick = {
                // TODO(port-d5-followup): wire a platform URL opener so
                // desktop can hand off to the OS map app or browser. For
                // now this is a no-op on commonMain; the production app/
                // version still launches geo:/OSM intents on Android.
                @Suppress("UNUSED_EXPRESSION") osmViewerUrl(lat, lng)
            }) { Text("Open") }
        }
    }
}

/**
 * Viewer-side rendering for the `[cal|…]` tag. Shows the event title +
 * human-friendly start/end, and an "Add to calendar" button that hands
 * off to the OS calendar app via Intent.ACTION_INSERT.
 */
@Composable
private fun CalWidgetBlock(
    startEpochMs: Long,
    endEpochMs: Long,
    title: String,
) {
    val start = remember(startEpochMs) { java.util.Date(startEpochMs) }
    val end = remember(endEpochMs) { java.util.Date(endEpochMs) }
    val summary = remember(startEpochMs, endEpochMs) { formatCalSummary(start, end) }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "📅 $title",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.TextButton(onClick = {
                // TODO(port-d5-followup): wire an OS calendar handoff for
                // desktop. Production Android still launches the
                // CalendarContract Intent.ACTION_INSERT path.
                @Suppress("UNUSED_EXPRESSION") startEpochMs
                @Suppress("UNUSED_EXPRESSION") endEpochMs
                @Suppress("UNUSED_EXPRESSION") title
            }) { Text("Add") }
        }
    }
}

/**
 * Viewer-side rendering for the `[tz|…]` tag. Shows the canonical US
 * zones + UTC, and adds the viewer's own zone at the top if it's not
 * already covered. A clock-emoji row draws attention without looking
 * like a regular code block.
 */
@Composable
private fun TzWidgetBlock(
    instantEpochMs: Long,
    sourceLabel: String,
) {
    val instant = remember(instantEpochMs) { java.util.Date(instantEpochMs) }
    val zones = remember {
        // Ordered: viewer's own zone first (when not already in the
        // canon list), then canonical US + UTC.
        val canon = listOf(
            "Eastern" to java.util.TimeZone.getTimeZone("America/New_York"),
            "Central" to java.util.TimeZone.getTimeZone("America/Chicago"),
            "Pacific" to java.util.TimeZone.getTimeZone("America/Los_Angeles"),
            "UTC" to java.util.TimeZone.getTimeZone("UTC"),
        )
        val viewer = java.util.TimeZone.getDefault()
        val viewerId = viewer.id
        val alreadyThere = canon.any { it.second.id == viewerId }
        if (alreadyThere) canon
        else {
            val label = viewer.getDisplayName(
                viewer.inDaylightTime(java.util.Date()),
                java.util.TimeZone.SHORT,
                java.util.Locale.getDefault(),
            ).ifEmpty { viewerId }
            listOf(label to viewer) + canon
        }
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                "🕒 sender sent from $sourceLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for ((label, zone) in zones) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        modifier = Modifier.widthIn(min = 72.dp),
                    )
                    Text(
                        formatInZone(instant, zone),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
