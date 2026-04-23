package io.nisfeb.talon.urbit

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Structured representation of a Tlon `story` (Verse[]) for rendering.
 * Each part is one block in the vertical column: either annotated text
 * with inline spans, an image, or a code block. Unknown shapes fall
 * through as bracketed tags so nothing silently disappears.
 */
sealed interface StoryPart {
    @androidx.compose.runtime.Immutable
    data class Text(val text: AnnotatedString) : StoryPart

    @androidx.compose.runtime.Immutable
    data class Image(
        val src: String,
        val width: Int?,
        val height: Int?,
        val alt: String?,
    ) : StoryPart

    @androidx.compose.runtime.Immutable
    data class Code(val code: String) : StoryPart

    /**
     * Server-enriched URL preview (Tlon's `block.link`). Metadata is
     * already filled by the agent — no client-side OG fetch needed.
     */
    @androidx.compose.runtime.Immutable
    data class LinkPreview(
        val url: String,
        val title: String?,
        val description: String?,
        val imageUrl: String?,
        val siteName: String?,
    ) : StoryPart

    /**
     * In-content citation to another post / channel / group. `chan`
     * cites point at a specific channel-post via nest + where; other
     * cite variants fall back to opaque labels.
     */
    @androidx.compose.runtime.Immutable
    data class Citation(
        val label: String,        // human-facing "Post in #channel" / "Group ~host/name"
        val openTarget: String?,  // whom-shaped string to open on tap, if any
        /**
         * Dotted @da suffix of the cited post, if the cite resolves to a
         * chat-channel message. Messages in our DB are keyed on
         * "~author/<dotted-da>" so callers can fuzzy-match by DA.
         */
        val postDa: String?,
        /** Dotted @da suffix of the cited reply, for reply cites. */
        val replyDa: String?,
    ) : StoryPart
}

/**
 * Parse the `where` path of a `chan` cite. Handles the three forms
 * observed in the wild (bare digits, dotted, or legacy "~author/<da>")
 * and returns the dotted @da of the post and (optionally) reply.
 *
 * Ported from yap's parseChanCiteWhere so cites round-trip the same way.
 */
fun parseChanCiteWhere(where: String): Pair<String, String?>? {
    val numericRe = Regex("([0-9][0-9.]*)")
    val raws = numericRe.findAll(where).map { it.value.replace(".", "") }.toList()
    if (raws.isEmpty()) return null
    val post = reDot(raws[0])
    val reply = raws.getOrNull(1)?.let(::reDot)
    return post to reply
}

private fun reDot(digits: String): String {
    val sb = StringBuilder()
    var i = digits.length
    while (i > 0) {
        val start = (i - 3).coerceAtLeast(0)
        if (sb.isNotEmpty()) sb.insert(0, '.')
        sb.insert(0, digits.substring(start, i))
        i = start
    }
    return sb.toString()
}

/** Public-facing tag for inline mention spans, so renderers can link-tap. */
const val MENTION_TAG = "mention"

/** Inline URL annotation tag — value is the absolute href. */
const val URL_TAG = "url"

object Story {

    /** Parse a JSON story into structured parts. Returns an empty list on junk. */
    fun parse(element: JsonElement?): List<StoryPart> {
        if (element == null) return emptyList()
        val verses = runCatching { element.jsonArray }.getOrNull() ?: return emptyList()
        val out = mutableListOf<StoryPart>()
        for (verse in verses) {
            val obj = runCatching { verse.jsonObject }.getOrNull() ?: continue
            obj["inline"]?.let { inline ->
                val arr = runCatching { inline.jsonArray }.getOrNull() ?: return@let
                val rendered = buildAnnotatedString {
                    arr.forEach { renderInline(it, this) }
                }
                if (rendered.isNotEmpty()) out.add(StoryPart.Text(rendered))
            }
            obj["block"]?.let { block ->
                val blockObj = runCatching { block.jsonObject }.getOrNull() ?: return@let
                renderBlock(blockObj)?.let(out::add)
            }
        }
        return out
    }

    /** Flat plain-text version — used for DM list previews and text search. */
    fun plainText(element: JsonElement?): String =
        parse(element).joinToString("\n") { part ->
            when (part) {
                is StoryPart.Text -> part.text.text
                is StoryPart.Image -> part.alt?.takeIf { it.isNotBlank() } ?: "[image]"
                is StoryPart.Code -> "```\n${part.code}\n```"
                is StoryPart.LinkPreview -> part.title ?: part.url
                is StoryPart.Citation -> part.label
            }
        }.trim()

    // ───────── inline spans ─────────

    private fun renderInline(element: JsonElement, out: androidx.compose.ui.text.AnnotatedString.Builder) {
        (element as? JsonPrimitive)?.let {
            out.append(if (it.isString) it.content else it.content)
            return
        }
        val obj = runCatching { element.jsonObject }.getOrNull() ?: return

        obj["break"]?.let { out.append('\n'); return }
        obj["ship"]?.jsonPrimitive?.let { prim ->
            val patp = if (prim.isString) prim.content else prim.content
            out.pushStringAnnotation(MENTION_TAG, patp)
            out.withSpan(SpanStyle(color = MENTION_COLOR, fontWeight = FontWeight.Medium)) {
                append(patp)
            }
            out.pop()
            return
        }
        obj["link"]?.jsonObject?.let { link ->
            val href = link["href"]?.jsonPrimitive?.contentOrNullSafe()
            val content = link["content"]?.jsonPrimitive?.contentOrNullSafe()
            val label = content ?: href ?: "[link]"
            if (href != null) out.pushStringAnnotation(URL_TAG, href)
            out.withSpan(SpanStyle(color = LINK_COLOR, textDecoration = TextDecoration.Underline)) {
                append(label)
            }
            if (href != null) out.pop()
            return
        }
        obj["italics"]?.let { arr ->
            out.withSpan(SpanStyle(fontStyle = FontStyle.Italic)) { renderInlineArray(arr, this) }
            return
        }
        obj["bold"]?.let { arr ->
            out.withSpan(SpanStyle(fontWeight = FontWeight.Bold)) { renderInlineArray(arr, this) }
            return
        }
        obj["strike"]?.let { arr ->
            out.withSpan(SpanStyle(textDecoration = TextDecoration.LineThrough)) { renderInlineArray(arr, this) }
            return
        }
        obj["code"]?.jsonPrimitive?.let {
            out.withSpan(MONO_SPAN) { append(if (it.isString) it.content else it.content) }
            return
        }
        obj["block-quote"]?.let { arr ->
            out.append("> ")
            renderInlineArray(arr, out)
            return
        }
        obj["task"]?.jsonObject?.let { task ->
            val checked = task["checked"]?.jsonPrimitive?.content == "true"
            out.append(if (checked) "[x] " else "[ ] ")
            task["content"]?.let { renderInlineArray(it, out) }
            return
        }

        // Unknown — emit a bracketed marker so debugging is obvious.
        out.append("[${obj.keys.firstOrNull() ?: "?"}]")
    }

    private fun renderInlineArray(
        element: JsonElement,
        out: androidx.compose.ui.text.AnnotatedString.Builder,
    ) {
        val arr = runCatching { element.jsonArray }.getOrNull() ?: return
        arr.forEach { renderInline(it, out) }
    }

    // ───────── blocks ─────────

    private fun renderBlock(block: JsonObject): StoryPart? {
        (block["image"] as? JsonObject)?.let { image ->
            val src = image["src"]?.jsonPrimitive?.contentOrNullSafe() ?: return null
            val width = image["width"]?.jsonPrimitive?.longOrNull?.toInt()
            val height = image["height"]?.jsonPrimitive?.longOrNull?.toInt()
            val alt = image["alt"]?.jsonPrimitive?.contentOrNullSafe()
            return StoryPart.Image(src = src, width = width, height = height, alt = alt)
        }
        (block["code"] as? JsonObject)?.let { code ->
            val body = code["code"]?.jsonPrimitive?.contentOrNullSafe() ?: ""
            return StoryPart.Code(body)
        }
        (block["link"] as? JsonObject)?.let { link ->
            // Tlon's server-enriched URL preview. `meta` is a flat string
            // bag — accept both kebab-case and camelCase keys defensively.
            val url = link["url"]?.jsonPrimitive?.contentOrNullSafe() ?: return null
            val meta = link["meta"] as? JsonObject ?: JsonObject(emptyMap())
            fun metaText(vararg keys: String): String? {
                for (k in keys) {
                    val v = meta[k]?.jsonPrimitive?.contentOrNullSafe()
                    if (!v.isNullOrBlank()) return v
                }
                return null
            }
            return StoryPart.LinkPreview(
                url = url,
                title = metaText("title"),
                description = metaText("description"),
                imageUrl = metaText("previewImageUrl", "preview-image-url"),
                siteName = metaText("siteName", "site-name"),
            )
        }
        (block["cite"] as? JsonObject)?.let { cite ->
            return renderCite(cite)
        }
        block["header"]?.jsonObject?.let { header ->
            val text = buildAnnotatedString {
                withSpan(SpanStyle(fontWeight = FontWeight.Bold)) {
                    renderInlineArray(header["content"] ?: JsonArray(emptyList()), this)
                }
            }
            if (text.isNotEmpty()) return StoryPart.Text(text)
        }
        // Unknown block kinds: walk one level deep looking for any
        // url-shaped field. Lets us render ad-hoc "file attachment"
        // shapes as a tappable LinkPreview instead of an opaque [tag].
        val tag = block.keys.firstOrNull() ?: "?"
        val inner = block[tag] as? JsonObject
        if (inner != null) {
            val url = inner["url"]?.jsonPrimitive?.contentOrNullSafe()
                ?: inner["href"]?.jsonPrimitive?.contentOrNullSafe()
                ?: inner["src"]?.jsonPrimitive?.contentOrNullSafe()
            if (!url.isNullOrBlank()) {
                val title = inner["title"]?.jsonPrimitive?.contentOrNullSafe()
                    ?: inner["name"]?.jsonPrimitive?.contentOrNullSafe()
                    ?: inner["alt"]?.jsonPrimitive?.contentOrNullSafe()
                    ?: url.substringAfterLast('/').substringBefore('?').ifBlank { url }
                return StoryPart.LinkPreview(
                    url = url,
                    title = title,
                    description = null,
                    imageUrl = null,
                    siteName = tag,
                )
            }
        }
        return StoryPart.Text(AnnotatedString("[$tag]"))
    }

    private fun renderCite(cite: JsonObject): StoryPart {
        (cite["chan"] as? JsonObject)?.let { chan ->
            val nest = chan["nest"]?.jsonPrimitive?.contentOrNullSafe()
            val where = chan["where"]?.jsonPrimitive?.contentOrNullSafe().orEmpty()
            val parsed = parseChanCiteWhere(where)
            val label = if (nest != null) {
                "Post in #" + nest.substringAfterLast('/')
            } else {
                "Channel post"
            }
            return StoryPart.Citation(
                label = label,
                openTarget = nest,
                postDa = parsed?.first,
                replyDa = parsed?.second,
            )
        }
        cite["group"]?.jsonPrimitive?.contentOrNullSafe()?.let { flag ->
            return StoryPart.Citation(
                label = "Group $flag",
                openTarget = null,
                postDa = null,
                replyDa = null,
            )
        }
        (cite["desk"] as? JsonObject)?.let { desk ->
            val flag = desk["flag"]?.jsonPrimitive?.contentOrNullSafe()
            return StoryPart.Citation(
                label = if (flag != null) "App $flag" else "App reference",
                openTarget = null,
                postDa = null,
                replyDa = null,
            )
        }
        return StoryPart.Citation(
            label = "Reference",
            openTarget = null,
            postDa = null,
            replyDa = null,
        )
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? =
        if (isString) content else null

    // ───────── style constants ─────────

    private val MENTION_COLOR = Color(0xFF4F63D2)
    private val LINK_COLOR = Color(0xFF2962FF)
    private val MONO_SPAN = SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
}

// Small local extension to compose span state + block write in one expression.
private inline fun androidx.compose.ui.text.AnnotatedString.Builder.withSpan(
    style: SpanStyle,
    block: androidx.compose.ui.text.AnnotatedString.Builder.() -> Unit,
) {
    val idx = pushStyle(style)
    try {
        block()
    } finally {
        pop(idx)
    }
}
