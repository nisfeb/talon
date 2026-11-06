package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * How a conversation draws its messages: as bubbles or as rows
 * ([UiSettings.chatBubbles]), and, in bubbles, on the background the
 * owner chose ([UiSettings.chatBackground]; null is the page's own).
 * Both shells provide it through [chatDensityLocals]; a screen that has
 * the settings itself provides its own for what it draws.
 */
@Immutable
data class ChatLook(val bubbles: Boolean = true, val background: Color? = null)

val LocalChatLook = staticCompositionLocalOf { ChatLook() }

/** Whose words a bubble holds, which is what colours it. */
enum class BubbleKind {
    /** The owner's own. */
    Mine,
    /** Someone in the owner's contact book. */
    Contact,
    /** Anyone else. */
    Theirs,
}

/** Whose words [author]'s are: the owner's, a contact's (in the [book]), or anyone's. */
fun bubbleKindOf(author: String, ourPatp: String, book: Set<String>): BubbleKind = when {
    author == ourPatp -> BubbleKind.Mine
    author in book -> BubbleKind.Contact
    else -> BubbleKind.Theirs
}

/** The bubble colours a theme sets; null for each it leaves to Auto. See [io.nisfeb.talon.ui.theme.CustomTheme]. */
@Immutable
data class BubbleColors(val mine: Color? = null, val contact: Color? = null, val theirs: Color? = null)

val LocalBubbleColors = staticCompositionLocalOf { BubbleColors() }

/** [kind]'s fill: the theme's, or Auto, which tints from the theme's own roles. */
@Composable
fun bubbleFill(kind: BubbleKind): Color {
    val set = LocalBubbleColors.current
    val scheme = MaterialTheme.colorScheme
    return when (kind) {
        BubbleKind.Mine -> set.mine ?: scheme.primary.copy(alpha = 0.22f)
        BubbleKind.Contact -> set.contact ?: scheme.tertiary.copy(alpha = 0.22f)
        // The raised colour, not surfaceVariant: quotes, link cards and
        // polls inside a message draw on that, and vanished into a bubble
        // of the same grey.
        BubbleKind.Theirs -> set.theirs ?: scheme.surfaceContainerHighest
    }
}

private fun contrast(a: Color, b: Color): Float {
    val (hi, lo) = a.luminance().let { la -> b.luminance().let { lb -> if (la > lb) la to lb else lb to la } }
    return (hi + 0.05f) / (lo + 0.05f)
}

/**
 * Text that reads on [fill], laid over [under]: the theme's own [text]
 * where it is 4.5:1 or better, else black or white, whichever reads more.
 * A theme or a background can make any bubble any colour.
 */
fun readableInk(fill: Color, under: Color, text: Color): Color {
    val solid = fill.compositeOver(under)
    if (contrast(text, solid) >= 4.5f) return text
    return if (contrast(Color.Black, solid) >= contrast(Color.White, solid)) Color.Black else Color.White
}

/**
 * A message's bubble: rounded, with a short tail toward its sender (ours
 * on the right), in [kind]'s colour, its text in an ink that reads on it.
 * The message's own content goes inside; the caller lays out the row.
 */
@Composable
fun MessageBubble(
    kind: BubbleKind,
    modifier: Modifier = Modifier,
    /** What the bubble sits on, for its ink: the chat's background, else the page. */
    under: Color = LocalChatLook.current.background ?: MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val fill = bubbleFill(kind)
    val corner = 18.dp
    val tail = 6.dp
    Surface(
        color = fill,
        contentColor = readableInk(fill, under, MaterialTheme.colorScheme.onSurface),
        shape = if (kind == BubbleKind.Mine) RoundedCornerShape(corner, corner, tail, corner)
            else RoundedCornerShape(corner, corner, corner, tail),
        modifier = modifier,
    ) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 7.dp, bottom = 5.dp), content = content)
    }
}
