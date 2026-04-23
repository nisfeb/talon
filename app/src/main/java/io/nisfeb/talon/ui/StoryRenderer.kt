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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import io.nisfeb.talon.TalonApplication
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
import coil.compose.AsyncImage
import io.nisfeb.talon.urbit.MENTION_TAG
import io.nisfeb.talon.urbit.StoryPart
import io.nisfeb.talon.urbit.URL_TAG

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
    val context = LocalContext.current
    val app = remember { context.applicationContext as TalonApplication }

    // Resolve cite → stored message, falling back to a channel scry
    // when the post isn't in our local window.
    var resolved by remember(cite) { mutableStateOf<MessageEntity?>(null) }
    LaunchedEffect(cite) {
        val whom = cite.openTarget ?: return@LaunchedEffect
        val da = cite.replyDa ?: cite.postDa ?: return@LaunchedEffect
        // 1. Try the local db first — free if we already have the post.
        app.db.messages().findByDa(whom, da)?.let { resolved = it; return@LaunchedEffect }
        // 2. Scry the channel. Works for chat/* nests; DM cites aren't
        //    supported by this path but we don't currently see them in
        //    the wild either.
        if (whom.startsWith("chat/")) {
            val fetched = if (cite.replyDa != null && cite.postDa != null) {
                app.repo.fetchCiteReply(whom, cite.postDa, cite.replyDa)
            } else {
                app.repo.fetchCitePost(whom, da)
            }
            resolved = fetched
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
