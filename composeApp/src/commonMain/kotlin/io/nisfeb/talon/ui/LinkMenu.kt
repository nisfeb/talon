package io.nisfeb.talon.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import io.nisfeb.talon.urbit.URL_TAG

/**
 * "Copy link" in the right-click menu of selectable text (the long-press
 * toolbar on a phone). The menu's own Copy takes the selection, never a
 * link's address.
 *
 * The link is the one the press landed on: [CopyLinkMenu] around the
 * SelectionContainer forgets it at every press, and [pressedLink] on
 * each Text inside records it again when the press is on a link.
 */
internal class LinkUnder { var url: String? = null }

@Composable
internal fun rememberLinkUnder(): LinkUnder = remember { LinkUnder() }

/** Goes around the SelectionContainer: menus take their items from ancestors. */
@Composable
internal fun CopyLinkMenu(link: LinkUnder, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val clipboard = LocalClipboardManager.current
    LinkMenuItems(
        url = { link.url },
        copy = { clipboard.setText(AnnotatedString(it)) },
        modifier = modifier.pointerInput(link) {
            // Initial runs outside in, so this clears before the text below records.
            awaitPointerEventScope {
                while (true) {
                    if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) link.url = null
                }
            }
        },
        content = content,
    )
}

/** Goes on a Text inside that SelectionContainer. */
internal fun Modifier.pressedLink(text: AnnotatedString, layout: () -> TextLayoutResult?, link: LinkUnder): Modifier =
    pointerInput(text, link) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                if (e.type != PointerEventType.Press) continue
                val pos = e.changes.firstOrNull()?.position ?: continue
                link.url = layout()?.urlAt(text, pos)
            }
        }
    }

/** A Text whose links [CopyLinkMenu] can copy. */
@Composable
internal fun LinkedText(
    text: AnnotatedString,
    link: LinkUnder,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(text, modifier.pressedLink(text, { layout }, link), color = color, style = style, onTextLayout = { layout = it })
}

/**
 * The glyph under [pos]: the one the pointer is on, not the caret
 * nearest it, so blank space past the end of a line is not the link
 * that ends it.
 */
internal fun TextLayoutResult.glyphAt(text: AnnotatedString, pos: Offset): Int? {
    val caret = getOffsetForPosition(pos)
    return listOf(caret, caret - 1).firstOrNull { it in text.indices && getBoundingBox(it).contains(pos) }
}

/** The address of the link under [pos], in either kind of link a text carries. */
internal fun TextLayoutResult.urlAt(text: AnnotatedString, pos: Offset): String? {
    val c = glyphAt(text, pos) ?: return null
    return text.getStringAnnotations(URL_TAG, c, c + 1).firstOrNull()?.item
        ?: text.getLinkAnnotations(c, c + 1).firstNotNullOfOrNull { (it.item as? LinkAnnotation.Url)?.url }
}

/**
 * "Copy link" for [url] in the menu of the text in [content], laid out
 * in a Box on [modifier]. Per platform: Android's text menu takes items
 * from a modifier, desktop's still from a ContextMenuDataProvider.
 */
@Composable
internal expect fun LinkMenuItems(url: () -> String?, copy: (String) -> Unit, modifier: Modifier, content: @Composable () -> Unit)

internal object CopyLinkKey
