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

    /**
     * Cross-zone clock widget emitted by `/tz`. The instant is encoded
     * UTC in the message tag; the renderer localizes to the viewer's
     * zones at display time.
     */
    @androidx.compose.runtime.Immutable
    data class TzWidget(
        val instantEpochMs: Long,
        val sourceLabel: String,
    ) : StoryPart

    /**
     * Calendar invite widget emitted by `/cal`. Renderer shows the
     * event + an "Add to calendar" button that launches the OS
     * calendar app via Intent.ACTION_INSERT.
     */
    @androidx.compose.runtime.Immutable
    data class CalWidget(
        val startEpochMs: Long,
        val endEpochMs: Long,
        val title: String,
    ) : StoryPart

    /**
     * Poll widget emitted by `/poll`. Voting happens via the normal
     * reaction UI — the keycap-digit emojis map 1:1 to options.
     */
    @androidx.compose.runtime.Immutable
    data class PollWidget(
        val question: String,
        val options: List<String>,
    ) : StoryPart

    /**
     * Location-share widget emitted by `/loc`. Renderer offers an
     * "Open map" action that hands off to the OS map app.
     */
    @androidx.compose.runtime.Immutable
    data class LocWidget(
        val lat: Double,
        val lng: Double,
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
                if (rendered.isNotEmpty()) out.addAll(splitForWidgetTags(rendered))
            }
            obj["block"]?.let { block ->
                val blockObj = runCatching { block.jsonObject }.getOrNull() ?: return@let
                renderBlock(blockObj)?.let(out::add)
            }
        }
        // Our slash commands send a human-readable preamble (so plain
        // clients see a usable fallback) followed by the structured
        // `[…|…]` tag. Each line lands in its own verse, so the
        // preamble survives the per-verse split. If the parsed parts
        // contain a widget, strip all Text parts — the widget's own
        // chrome carries the presentation on Talon.
        val hasWidget = out.any { it.isStoryWidget() }
        return if (hasWidget) out.filter { it !is StoryPart.Text } else out
    }

    private fun StoryPart.isStoryWidget(): Boolean = when (this) {
        is StoryPart.TzWidget, is StoryPart.CalWidget,
        is StoryPart.PollWidget, is StoryPart.LocWidget -> true
        else -> false
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
                is StoryPart.TzWidget -> "[tz]"
                is StoryPart.CalWidget -> "[cal] ${part.title}"
                is StoryPart.PollWidget -> "📊 ${part.question}"
                is StoryPart.LocWidget -> "📍 ${part.lat}, ${part.lng}"
            }
        }.trim()

    /**
     * Split a rendered AnnotatedString around the first widget tag we
     * recognize (`[tz|…]`, `[cal|…]`, …). Returns leading Text +
     * widget + trailing Text, stripping the tag from display. Only
     * handles a single tag per text block — messages with more than
     * one widget are rare and the tail goes back through [splitForWidgetTags]
     * recursively.
     */
    private fun splitForWidgetTags(text: androidx.compose.ui.text.AnnotatedString): List<StoryPart> {
        // Find the earliest widget match of any kind.
        val tz = io.nisfeb.talon.ui.TZ_TAG_RE.find(text.text)
        val cal = io.nisfeb.talon.ui.CAL_TAG_RE.find(text.text)
        val poll = io.nisfeb.talon.ui.POLL_TAG_RE.find(text.text)
        val loc = io.nisfeb.talon.ui.LOC_TAG_RE.find(text.text)
        val first = listOfNotNull(tz, cal, poll, loc).minByOrNull { it.range.first }
            ?: return listOf(StoryPart.Text(text))

        val maybeWidget: StoryPart? = when (first) {
            tz -> {
                val instant = io.nisfeb.talon.ui.parseIsoUtc(first.groupValues[1])
                instant?.let { StoryPart.TzWidget(it.time, first.groupValues[2]) }
            }
            cal -> {
                io.nisfeb.talon.ui.decodeCalTag(first.value)?.let { d ->
                    StoryPart.CalWidget(d.start.time, d.end.time, d.title)
                }
            }
            poll -> {
                io.nisfeb.talon.ui.decodePollTag(first.value)?.let { p ->
                    StoryPart.PollWidget(p.question, p.options)
                }
            }
            loc -> {
                io.nisfeb.talon.ui.decodeLocTag(first.value)?.let { d ->
                    StoryPart.LocWidget(d.lat, d.lng)
                }
            }
            else -> null
        }
        val widget: StoryPart = maybeWidget ?: return listOf(StoryPart.Text(text))

        // Our slash commands embed a plain-text summary alongside the
        // tag so non-widget clients (plain Tlon, older Talon) see the
        // event/poll/location. On Talon we only want the widget — the
        // structured card is the canonical presentation, and the text
        // lines are just a duplicate. Drop everything around the tag.
        return listOf(widget)
    }

    private fun trimEndBlanks(s: androidx.compose.ui.text.AnnotatedString): androidx.compose.ui.text.AnnotatedString {
        var end = s.length
        while (end > 0 && s.text[end - 1].let { it == '\n' || it == ' ' || it == '\t' }) end--
        return s.subSequence(0, end)
    }

    private fun trimStartBlanks(s: androidx.compose.ui.text.AnnotatedString): androidx.compose.ui.text.AnnotatedString {
        var start = 0
        while (start < s.length && s.text[start].let { it == '\n' || it == ' ' || it == '\t' }) start++
        return s.subSequence(start, s.length)
    }

    // ───────── inline spans ─────────

    private fun renderInline(element: JsonElement, out: androidx.compose.ui.text.AnnotatedString.Builder) {
        (element as? JsonPrimitive)?.let {
            out.append(if (it.isString) it.content else it.content)
            return
        }
        val obj = runCatching { element.jsonObject }.getOrNull() ?: return

        obj["break"]?.let { out.append('\n'); return }
        (obj["ship"] as? JsonPrimitive)?.let { prim ->
            val patp = if (prim.isString) prim.content else prim.content
            out.pushStringAnnotation(MENTION_TAG, patp)
            out.withSpan(SpanStyle(color = MENTION_COLOR, fontWeight = FontWeight.Medium)) {
                append(patp)
            }
            out.pop()
            return
        }
        (obj["link"] as? JsonObject)?.let { link ->
            val href = link["href"].asStr()
            val content = link["content"].asStr()
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
        // Tlon emits both `code` (older) and `inline-code` (newer) for
        // monospace spans — treat them identically.
        ((obj["code"] ?: obj["inline-code"]) as? JsonPrimitive)?.let {
            out.withSpan(MONO_SPAN) { append(if (it.isString) it.content else it.content) }
            return
        }
        // Tlon has emitted both kebab (`block-quote`) and single-word
        // (`blockquote`) over the years — accept either.
        (obj["block-quote"] ?: obj["blockquote"])?.let { arr ->
            out.withSpan(
                SpanStyle(
                    fontStyle = FontStyle.Italic,
                    color = Color.Gray,
                ),
            ) {
                out.append("“")
                renderInlineArray(arr, out)
                out.append("”")
            }
            return
        }
        (obj["task"] as? JsonObject)?.let { task ->
            val checked = task["checked"].asText() == "true"
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

    /**
     * Tlon's `block.listing` is a recursive structure:
     *   { "list": {
     *       "type": "ordered"|"unordered",
     *       "contents": [inline…],   // optional intro before items
     *       "items":    [ { "item": [inline…] }
     *                   | { "list": {…} }      // nested sub-list
     *                   ]
     *   } }
     * We flatten to a single AnnotatedString with bullets / numbers +
     * indentation per level. Good enough to read; visually simple.
     */
    private fun renderListing(
        listing: JsonObject,
        out: androidx.compose.ui.text.AnnotatedString.Builder,
        depth: Int,
    ) {
        val list = listing["list"] as? JsonObject ?: return
        renderList(list, out, depth)
    }

    private fun renderList(
        list: JsonObject,
        out: androidx.compose.ui.text.AnnotatedString.Builder,
        depth: Int,
    ) {
        val ordered = list["type"].asStr() == "ordered"
        val contents = list["contents"] as? JsonArray
        val items = list["items"] as? JsonArray ?: return

        val indent = "  ".repeat(depth)

        // Optional intro text ("Next steps:" style) appears before items.
        if (contents != null && contents.isNotEmpty()) {
            if (out.length > 0 && !out.toString().endsWith('\n')) out.append('\n')
            out.append(indent)
            renderInlineArray(contents, out)
        }

        items.forEachIndexed { idx, raw ->
            val itemObj = raw as? JsonObject ?: return@forEachIndexed
            (itemObj["item"] as? JsonArray)?.let { inlineArr ->
                if (out.length > 0 && !out.toString().endsWith('\n')) out.append('\n')
                val bullet = if (ordered) "${idx + 1}. " else "• "
                out.append(indent)
                out.append(bullet)
                renderInlineArray(inlineArr, out)
                return@forEachIndexed
            }
            (itemObj["list"] as? JsonObject)?.let { nested ->
                renderList(nested, out, depth + 1)
            }
        }
    }

    // ───────── blocks ─────────

    private fun renderBlock(block: JsonObject): StoryPart? {
        (block["image"] as? JsonObject)?.let { image ->
            val src = image["src"].asStr() ?: return null
            val width = image["width"].asLong()?.toInt()
            val height = image["height"].asLong()?.toInt()
            val alt = image["alt"].asStr()
            return StoryPart.Image(src = src, width = width, height = height, alt = alt)
        }
        (block["code"] as? JsonObject)?.let { code ->
            val body = code["code"].asStr() ?: ""
            return StoryPart.Code(body)
        }
        (block["link"] as? JsonObject)?.let { link ->
            // Tlon's server-enriched URL preview. `meta` is a flat string
            // bag — accept both kebab-case and camelCase keys defensively.
            val url = link["url"].asStr() ?: return null
            val meta = link["meta"] as? JsonObject ?: JsonObject(emptyMap())
            fun metaText(vararg keys: String): String? {
                for (k in keys) {
                    val v = meta[k].asStr()
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
        (block["header"] as? JsonObject)?.let { header ->
            val text = buildAnnotatedString {
                withSpan(SpanStyle(fontWeight = FontWeight.Bold)) {
                    renderInlineArray(header["content"] ?: JsonArray(emptyList()), this)
                }
            }
            if (text.isNotEmpty()) return StoryPart.Text(text)
        }
        (block["listing"] as? JsonObject)?.let { listing ->
            val text = buildAnnotatedString {
                renderListing(listing, this, depth = 0)
            }
            if (text.isNotEmpty()) return StoryPart.Text(text)
        }
        // Some Tlon versions wrap multi-line blockquotes at block level
        // instead of inline. Accept both tag spellings and render the
        // contained inlines with quote styling.
        (block["block-quote"] ?: block["blockquote"])?.let { content ->
            val text = buildAnnotatedString {
                withSpan(
                    SpanStyle(
                        fontStyle = FontStyle.Italic,
                        color = Color.Gray,
                    ),
                ) {
                    append("“")
                    when (content) {
                        is JsonArray -> renderInlineArray(content, this@buildAnnotatedString)
                        is JsonObject -> {
                            // Older format: { "block-quote": { "content": [...] } }
                            val inner = content["content"] ?: content["inline"]
                            if (inner != null) renderInlineArray(inner, this@buildAnnotatedString)
                        }
                        else -> {}
                    }
                    append("”")
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
            val url = inner["url"].asStr()
                ?: inner["href"].asStr()
                ?: inner["src"].asStr()
            if (!url.isNullOrBlank()) {
                val baseName = inner["title"].asStr()
                    ?: inner["name"].asStr()
                    ?: inner["alt"].asStr()
                    ?: url.substringAfterLast('/').substringBefore('?').ifBlank { url }
                val size = inner["size"].asLong()
                val title = if (size != null) {
                    "$baseName • ${humanFileSize(size)}"
                } else baseName
                val siteLabel = when (tag) {
                    "file" -> "📎 File"
                    else -> tag
                }
                return StoryPart.LinkPreview(
                    url = url,
                    title = title,
                    description = inner["mime"].asStr(),
                    imageUrl = null,
                    siteName = siteLabel,
                )
            }
        }
        return StoryPart.Text(AnnotatedString("[$tag]"))
    }

    private fun renderCite(cite: JsonObject): StoryPart {
        val parsed = parseCite(cite)
        return when (val t = parsed.target) {
            is CiteTarget.ChannelPost -> StoryPart.Citation(
                label = parsed.label,
                openTarget = t.nest,
                postDa = t.postDa,
                replyDa = t.replyDa,
            )
            is CiteTarget.Group -> StoryPart.Citation(
                label = parsed.label,
                openTarget = "group:${t.flag}",
                postDa = null,
                replyDa = null,
            )
            is CiteTarget.Url -> StoryPart.LinkPreview(
                url = t.url,
                title = t.title ?: parsed.label,
                description = null,
                imageUrl = null,
                siteName = "Reference",
            )
            null -> {
                if (parsed.label == "Reference") {
                    android.util.Log.w(
                        "StoryCite",
                        "unhandled cite shape: keys=${cite.keys} " +
                            "preview=${cite.toString().take(400)}",
                    )
                }
                StoryPart.Citation(
                    label = parsed.label,
                    openTarget = null,
                    postDa = null,
                    replyDa = null,
                )
            }
        }
    }

    private fun humanFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }

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
