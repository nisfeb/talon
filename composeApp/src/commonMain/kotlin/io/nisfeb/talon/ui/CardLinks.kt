package io.nisfeb.talon.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import io.nisfeb.talon.urbit.isValidPatp

/** The address a tap on someone opens: their chat. */
fun dmLink(ship: String): String = "dm:$ship"

/** The ship a [dmLink] opens, or null for any other address. */
fun dmShip(uri: String): String? = uri.removePrefix("dm:").takeIf { uri.startsWith("dm:") && isValidPatp(it) }

private val PATP = Regex("""(?<![\w~-])~[a-z]{3,6}(?:-[a-z]{6})*(?![\w-])""")
private val BARE_URL = Regex("""(?i)\b(?:https?://|urb://|talon://|www\.)[^\s<>"'`]+""")
private const val TRAILING = ".,;:!?)]"

/**
 * [this] with each @p, bare link, and any of [names] made a link: an @p
 * or a name opens that person's chat ([dmLink]), a link opens where it
 * points. Links already in the text (a markdown `[label](url)`) are left
 * as they are, and nothing is linked twice. sneagan, 2026-10-16: "make
 * usernames and @p interactive".
 */
fun AnnotatedString.withTalonLinks(names: Map<String, String>, linkColor: Color? = null): AnnotatedString {
    val text = this.text
    val taken = getLinkAnnotations(0, text.length).map { it.start until it.end }.toMutableList()
    fun free(r: IntRange) = taken.none { it.first <= r.last && r.first <= it.last }
    val found = mutableListOf<Pair<IntRange, String>>()
    fun add(r: IntRange, url: String) {
        if (r.isEmpty() || !free(r)) return
        taken += r
        found += r to url
    }
    BARE_URL.findAll(text).forEach { m ->
        var end = m.range.last
        while (end > m.range.first && text[end] in TRAILING) end--
        val raw = text.substring(m.range.first, end + 1)
        add(m.range.first..end, if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw)
    }
    PATP.findAll(text).forEach { m -> if (isValidPatp(m.value)) add(m.range, dmLink(m.value)) }
    // Longest names first, so "Andrea Egan" is one link and not "Andrea" and a stray "Egan".
    names.entries.sortedByDescending { it.key.length }.forEach { (name, ship) ->
        Regex("""(?<![\w])${Regex.escape(name)}(?![\w])""", RegexOption.IGNORE_CASE).findAll(text).forEach { add(it.range, dmLink(ship)) }
    }
    if (found.isEmpty()) return this
    val style = TextLinkStyles(style = linkSpan(linkColor))
    return buildAnnotatedString {
        append(this@withTalonLinks)
        found.forEach { (r, url) -> addLink(LinkAnnotation.Url(url, style), r.first, r.last + 1) }
    }
}
