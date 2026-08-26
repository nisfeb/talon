package io.nisfeb.talon.urbit

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import io.nisfeb.talon.ui.EmojiCatalog
import io.nisfeb.talon.util.Log
import io.nisfeb.talon.util.formatDecimals
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

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
     * GFM table. `header` is one cell per column; `rows` is a list of
     * rows, each a list of cells. Cells carry inline styling. Synthesised
     * by render-time markdown ([MarkdownBlocks]) — Tlon has no table verse.
     */
    @androidx.compose.runtime.Immutable
    data class Table(
        val header: List<AnnotatedString>,
        val rows: List<List<AnnotatedString>>,
    ) : StoryPart

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
private val NUMERIC_DOTTED_RE = Regex("([0-9][0-9.]*)")

fun parseChanCiteWhere(where: String): Pair<String, String?>? {
    val raws = NUMERIC_DOTTED_RE.findAll(where).map { it.value.replace(".", "") }.toList()
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

    /**
     * Parse a JSON story into structured parts. Returns an empty list on junk.
     *
     * [expandMarkdown] turns on render-time markdown: any plain-text span
     * (from a bot or a foreign client that never structured it) is
     * re-flowed through the same parsers the composer uses — inline styles
     * everywhere, and block constructs (headings, lists, fenced code,
     * blockquotes, rules) when a whole verse is nothing but plain text.
     * Already-structured spans are trusted untouched. Always pass `false`
     * when re-parsing parser output to avoid infinite recursion.
     */
    fun parse(element: JsonElement?, expandMarkdown: Boolean = true): List<StoryPart> {
        if (element == null) return emptyList()
        val verses = element as? JsonArray ?: return emptyList()
        val out = mutableListOf<StoryPart>()
        for (verse in verses) {
            val obj = verse as? JsonObject ?: continue
            obj["inline"]?.let { inline ->
                val arr = inline as? JsonArray ?: return@let
                // A verse that's only plain text + line breaks can carry
                // raw block markdown. Re-flow it through MarkdownBlocks
                // (the notebook composer's parser) and parse the result
                // with expansion OFF. Skip the round-trip for prose with
                // no block markers — inline re-parsing below covers that.
                if (expandMarkdown && isPlainTextInline(arr)) {
                    val raw = reconstructPlainText(arr)
                    if (hasBlockMarkdown(raw)) {
                        out.addAll(parse(MarkdownBlocks.toStory(raw), expandMarkdown = false))
                        return@let
                    }
                }
                val rendered = buildAnnotatedString {
                    arr.forEach { renderInline(it, this, expandMarkdown) }
                }
                // Trim a trailing `break` (`\n`) from the verse so it
                // doesn't render as a blank row on top of the column-
                // arrangement gap. Older Talon clients (and anyone
                // splitting one-line-per-verse) leave a stray `\n` at
                // the end; without this trim they double-space.
                val cleaned = trimEndBlanks(rendered)
                if (cleaned.isNotEmpty()) out.addAll(splitForWidgetTags(cleaned))
            }
            obj["block"]?.let { block ->
                val blockObj = block as? JsonObject ?: return@let
                renderBlock(blockObj, expandMarkdown)?.let(out::add)
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
                is StoryPart.Table -> (listOf(part.header) + part.rows)
                    .joinToString("\n") { row -> row.joinToString(" | ") { it.text } }
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
        // TODO(port/wave2): wire up widget-tag parsing (TZ/Cal/Poll/Loc) once
        // io.nisfeb.talon.ui.{TZ_TAG_RE, CAL_TAG_RE, …} are ported to commonMain.
        // For now, return the text block as-is — widgets will render as plain text.
        return listOf(StoryPart.Text(text))
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

    /**
     * Append [text] to [out], but linkify any bare `urb://` runs:
     * Tlon's server-side linkifier only wraps http(s) URLs in `link`
     * blocks, so urb:// addresses arrive as plain text. We annotate
     * those runs with [URL_TAG] (the same tag http links use) so the
     * renderer makes them tappable — the tap site branches on the
     * urb:// prefix to route through Lattice instead of the browser.
     */
    /** `:shortcode:` → emoji glyph for known names; unknown names pass
     *  through untouched. Applied to plain body text only (never code
     *  spans), so `` `:tada:` `` stays literal. */
    private val EMOJI_SHORTCODE_RE = Regex(":[a-z0-9_+-]+:")
    private fun replaceEmojiShortcodes(text: String): String {
        if (':' !in text) return text
        return EMOJI_SHORTCODE_RE.replace(text) { m -> EmojiCatalog.glyphFor(m.value) ?: m.value }
    }

    private fun appendLinkifyingUrb(raw: String, out: androidx.compose.ui.text.AnnotatedString.Builder) {
        val text = replaceEmojiShortcodes(raw)
        val ranges = UrbLink.findRanges(text)
        if (ranges.isEmpty()) {
            out.append(text)
            return
        }
        var cursor = 0
        for (r in ranges) {
            if (r.first > cursor) out.append(text.substring(cursor, r.first))
            val url = text.substring(r.first, r.last + 1)
            out.pushStringAnnotation(URL_TAG, url)
            out.withSpan(SpanStyle(color = LINK_COLOR, textDecoration = TextDecoration.Underline)) {
                append(url)
            }
            out.pop()
            cursor = r.last + 1
        }
        if (cursor < text.length) out.append(text.substring(cursor))
    }

    /** True if every inline element is a plain string or a `break` — i.e.
     *  the verse has no structured spans we'd drop by re-flowing it
     *  through the markdown parser. */
    private fun isPlainTextInline(arr: JsonArray): Boolean = arr.all { el ->
        when (el) {
            is JsonPrimitive -> el.isString
            is JsonObject -> el.size == 1 && el.containsKey("break")
            else -> false
        }
    }

    /** Flatten a plain-text inline array back to source text, turning
     *  `break` spans into newlines so multi-line block markdown is
     *  recoverable. */
    private fun reconstructPlainText(arr: JsonArray): String = buildString {
        arr.forEach { el ->
            when (el) {
                is JsonPrimitive -> if (el.isString) append(el.content)
                is JsonObject -> if (el.containsKey("break")) append('\n')
                else -> {}
            }
        }
    }

    /** Cheap pre-check: does any line open a block construct MarkdownBlocks
     *  would promote (heading, quote, fence, rule, list)? Lets ordinary
     *  prose skip the block round-trip — inline re-parsing handles it. */
    private fun hasBlockMarkdown(text: String): Boolean =
        text.replace("\r\n", "\n").split('\n').any { line ->
            line.startsWith("# ") || line.startsWith("## ") || line.startsWith("### ") ||
                line.startsWith("> ") || line.startsWith("```") ||
                line.startsWith("---") || line.startsWith("***") ||
                MarkdownBlocks.isListLine(line) || MarkdownBlocks.isTableSeparator(line)
        }

    /** Cheap pre-check before running the inline parser on a plain span:
     *  bail unless it holds a char that could open inline markup. */
    private fun mightHaveInlineMarkup(s: String): Boolean =
        s.any { it == '*' || it == '_' || it == '`' || it == '[' || it == '~' } ||
            s.contains("://")

    private fun renderInline(
        element: JsonElement,
        out: androidx.compose.ui.text.AnnotatedString.Builder,
        expandMarkdown: Boolean = true,
    ) {
        (element as? JsonPrimitive)?.let {
            val raw = it.content
            // Render-time inline markdown: a plain span may hold raw
            // **bold**, *italic*, `code`, [links](url), bare URLs or
            // ~mentions. Re-flow through the composer's inline parser,
            // then render the result with expansion OFF so a plain leaf
            // can't recurse forever.
            if (expandMarkdown && mightHaveInlineMarkup(raw)) {
                Markdown.parseInlines(raw).forEach { span ->
                    renderInline(span, out, expandMarkdown = false)
                }
            } else {
                appendLinkifyingUrb(raw, out)
            }
            return
        }
        val obj = element as? JsonObject ?: return

        obj["break"]?.let { out.append('\n'); return }
        (obj["ship"] as? JsonPrimitive)?.let { prim ->
            val patp = if (prim.isString) prim.content else prim.content
            // The wire carries the @p; what the reader *sees* is their
            // own naming preference (nickname / mnemonym / @p), so one
            // person's nickname for someone never leaks into another
            // person's view. The annotation stays the @p so taps still
            // resolve the right ship.
            out.pushStringAnnotation(MENTION_TAG, patp)
            out.withSpan(SpanStyle(color = MENTION_COLOR, fontWeight = FontWeight.Medium)) {
                append(io.nisfeb.talon.ui.ShipNames.resolve(patp))
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
            out.withSpan(SpanStyle(fontStyle = FontStyle.Italic)) { renderInlineArray(arr, this, expandMarkdown) }
            return
        }
        obj["bold"]?.let { arr ->
            out.withSpan(SpanStyle(fontWeight = FontWeight.Bold)) { renderInlineArray(arr, this, expandMarkdown) }
            return
        }
        obj["strike"]?.let { arr ->
            out.withSpan(SpanStyle(textDecoration = TextDecoration.LineThrough)) { renderInlineArray(arr, this, expandMarkdown) }
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
                renderInlineArray(arr, out, expandMarkdown)
                out.append("”")
            }
            return
        }
        (obj["task"] as? JsonObject)?.let { task ->
            val checked = task["checked"].asText() == "true"
            out.append(if (checked) "[x] " else "[ ] ")
            task["content"]?.let { renderInlineArray(it, out, expandMarkdown) }
            return
        }

        // Unknown — emit a bracketed marker so debugging is obvious.
        out.append("[${obj.keys.firstOrNull() ?: "?"}]")
    }

    private fun renderInlineArray(
        element: JsonElement,
        out: androidx.compose.ui.text.AnnotatedString.Builder,
        expandMarkdown: Boolean = true,
    ) {
        val arr = element as? JsonArray ?: return
        arr.forEach { renderInline(it, out, expandMarkdown) }
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
        expandMarkdown: Boolean = true,
    ) {
        val list = listing["list"] as? JsonObject ?: return
        renderList(list, out, depth, expandMarkdown)
    }

    private class TaskItem(val checked: Boolean, val rest: JsonArray)

    private val TASK_PREFIX_RE = Regex("^\\[([ xX])]\\s+")

    /** If a list item's first inline span opens with `[ ]` / `[x]`, return
     *  the checked state plus the item's inlines with that marker stripped;
     *  null for an ordinary list item. */
    private fun taskItem(inlineArr: JsonArray): TaskItem? {
        val first = inlineArr.firstOrNull() as? JsonPrimitive ?: return null
        if (!first.isString) return null
        val m = TASK_PREFIX_RE.find(first.content) ?: return null
        val checked = first.content[1].lowercaseChar() == 'x'
        val tail = first.content.substring(m.range.last + 1)
        val rest = buildJsonArray {
            if (tail.isNotEmpty()) add(JsonPrimitive(tail))
            inlineArr.drop(1).forEach { add(it) }
        }
        return TaskItem(checked, rest)
    }

    private fun renderList(
        list: JsonObject,
        out: androidx.compose.ui.text.AnnotatedString.Builder,
        depth: Int,
        expandMarkdown: Boolean = true,
    ) {
        val ordered = list["type"].asStr() == "ordered"
        val contents = list["contents"] as? JsonArray
        val items = list["items"] as? JsonArray ?: return

        val indent = "  ".repeat(depth)

        // Track whether the builder currently ends with '\n' so we can
        // skip the per-iteration `out.toString().endsWith('\n')` —
        // that materializes the entire builder and turns the loop into
        // O(N²) on large lists. The fallback toString() runs at most
        // once per call (on the first iteration where we don't yet
        // know what the outer caller left in `out`).
        var endsWithNewline: Boolean? = null
        fun ensureNewline() {
            if (out.length == 0) {
                endsWithNewline = true
                return
            }
            val known = endsWithNewline
                ?: out.toString().endsWith('\n').also { endsWithNewline = it }
            if (!known) {
                out.append('\n')
                endsWithNewline = true
            }
        }

        // Optional intro text ("Next steps:" style) appears before items.
        if (contents != null && contents.isNotEmpty()) {
            ensureNewline()
            out.append(indent)
            renderInlineArray(contents, out, expandMarkdown)
            // Inline content doesn't terminate with '\n' by convention
            // (the only way it does is a trailing `break` inline; the
            // worst-case cost of a wrong guess here is one redundant
            // newline appended next iteration — a blank line visually).
            endsWithNewline = false
        }

        items.forEachIndexed { idx, raw ->
            val itemObj = raw as? JsonObject ?: return@forEachIndexed
            (itemObj["item"] as? JsonArray)?.let { inlineArr ->
                ensureNewline()
                out.append(indent)
                // Task-list item ("- [ ] todo" / "- [x] done"): a checkbox
                // glyph replaces the bullet, and the `[ ]`/`[x]` marker is
                // stripped from the rendered text.
                val task = taskItem(inlineArr)
                if (task != null) {
                    out.append(if (task.checked) "☑ " else "☐ ")
                    renderInlineArray(task.rest, out, expandMarkdown)
                } else {
                    out.append(if (ordered) "${idx + 1}. " else "• ")
                    renderInlineArray(inlineArr, out, expandMarkdown)
                }
                endsWithNewline = false
                return@forEachIndexed
            }
            (itemObj["list"] as? JsonObject)?.let { nested ->
                renderList(nested, out, depth + 1, expandMarkdown)
                endsWithNewline = null  // recursion: unknown, force re-check
            }
        }
    }

    // ───────── blocks ─────────

    private fun renderBlock(block: JsonObject, expandMarkdown: Boolean = true): StoryPart? {
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
                    renderInlineArray(header["content"] ?: JsonArray(emptyList()), this, expandMarkdown)
                }
            }
            if (text.isNotEmpty()) return StoryPart.Text(text)
        }
        (block["listing"] as? JsonObject)?.let { listing ->
            val text = buildAnnotatedString {
                renderListing(listing, this, depth = 0, expandMarkdown = expandMarkdown)
            }
            if (text.isNotEmpty()) return StoryPart.Text(text)
        }
        (block["table"] as? JsonObject)?.let { table ->
            fun cells(arr: JsonArray?): List<AnnotatedString> =
                (arr ?: JsonArray(emptyList())).map { cell ->
                    buildAnnotatedString { renderInlineArray(cell, this, expandMarkdown) }
                }
            val header = cells(table["header"] as? JsonArray)
            val rows = (table["rows"] as? JsonArray)?.map { cells(it as? JsonArray) }.orEmpty()
            if (header.isNotEmpty() || rows.isNotEmpty()) {
                return StoryPart.Table(header, rows)
            }
        }
        // Horizontal rule (`---` / `***`). Tlon has no rule glyph; render
        // a thin divider line of box-drawing chars so it reads as a break
        // instead of the literal `[rule]` the unknown-block path would emit.
        if (block.containsKey("rule")) {
            return StoryPart.Text(AnnotatedString("─".repeat(24)))
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
                        is JsonArray -> renderInlineArray(content, this@buildAnnotatedString, expandMarkdown)
                        is JsonObject -> {
                            // Older format: { "block-quote": { "content": [...] } }
                            val inner = content["content"] ?: content["inline"]
                            if (inner != null) renderInlineArray(inner, this@buildAnnotatedString, expandMarkdown)
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
                    Log.w("StoryCite", "unhandled cite shape: keys=${cite.keys} preview=${cite.toString().take(400)}")
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
        if (kb < 1024) return "${kb.formatDecimals(1)} KB"
        val mb = kb / 1024.0
        if (mb < 1024) return "${mb.formatDecimals(1)} MB"
        return "${(mb / 1024.0).formatDecimals(2)} GB"
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
