package io.nisfeb.talon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/** Lines of text a post shows before it folds. */
const val FOLD_LINES = 10

/**
 * A long post folded to its first [FOLD_LINES] lines, with Show more and
 * Show less: a long post took the whole screen and the chat around it
 * took scrolling to get past. A post with a picture gets the picture's
 * room on top, so a photo and a line under it never fold. Open or shut
 * is kept per post while the chat scrolls.
 */
@Composable
fun FoldLongPost(id: String, hasMedia: Boolean, content: @Composable () -> Unit) {
    var open by rememberSaveable(id) { mutableStateOf(false) }
    val full = remember(id) { mutableIntStateOf(0) }
    val line = MaterialTheme.typography.bodyMedium.lineHeight
    val limit = with(LocalDensity.current) { (line * FOLD_LINES).toPx() + if (hasMedia) MEDIA_ROOM.toPx() else 0f }.toInt()
    // A little over is not worth a fold: two more lines shown beat a
    // button that reveals two lines. Derived, so a row's measured height
    // recomposes it only when it crosses the line: every short post in a
    // chat recomposed once more after its first layout.
    val long by remember(id, limit) { derivedStateOf { full.intValue > limit + limit / 5 } }
    Column {
        Box(
            Modifier.clipToBounds().layout { m, c ->
                val body = m.measure(c.copy(maxHeight = Constraints.Infinity))
                val shown = if (long && !open) minOf(body.height, limit) else body.height
                layout(body.width, shown) { body.placeRelative(0, 0) }
            },
        ) {
            Box(Modifier.onSizeChanged { full.intValue = it.height }) { content() }
        }
        if (long) {
            Text(
                if (open) "Show less" else "Show more",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp).clickable { open = !open },
            )
        }
    }
}

/** The tallest a picture in a post is drawn (StoryRenderer's image). */
private val MEDIA_ROOM = 360.dp

/** Whether a post shows a picture or other media, which [FoldLongPost] makes room for. */
fun hasMedia(parts: List<io.nisfeb.talon.urbit.StoryPart>): Boolean =
    parts.any { it is io.nisfeb.talon.urbit.StoryPart.Image } || mediaInStory(parts).isNotEmpty()

/**
 * Whether a post could run past [FOLD_LINES] lines: enough line breaks,
 * text or blocks. Only those are measured for a fold; measuring every
 * row cost the chat a layout and a recomposition each, and a jump
 * through a long chat was the slower for it.
 */
fun mightFold(parts: List<io.nisfeb.talon.urbit.StoryPart>): Boolean {
    var lines = 0
    var chars = 0
    for (p in parts) {
        val text = when (p) {
            is io.nisfeb.talon.urbit.StoryPart.Text -> p.text.text
            is io.nisfeb.talon.urbit.StoryPart.Code -> p.code
            else -> null
        } ?: continue
        lines += text.count { it == '\n' } + 1
        chars += text.length
    }
    return lines >= FOLD_LINES || chars >= FOLD_LINES * 30 || parts.size >= FOLD_LINES
}

