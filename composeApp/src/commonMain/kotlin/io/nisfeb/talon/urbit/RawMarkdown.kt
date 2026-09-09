package io.nisfeb.talon.urbit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Inverse of [Markdown.parseInlines] + [MarkdownBlocks.toStory]: take a
 * Tlon Story `contentJson` (the wire shape the ship sends and stores)
 * and reconstruct the markdown source the user typed.
 *
 * Powers the "Copy as Markdown" message action — a paste-friendly,
 * lossless copy that preserves bold / italic / inline code / links /
 * mentions / blockquotes instead of flattening them like
 * [StoryCache.textFor] does. Use this for "I want to forward / quote /
 * archive the source"; use `textFor` for "I want a plain preview".
 *
 * Anything we don't recognise round-trips as best-effort plain text so
 * a copy is never empty. The standard span shapes match
 * yap/ui/src/util/markdown.ts and tlon-apps' chat backend.
 */
object RawMarkdown {

    private val json = Json { ignoreUnknownKeys = true }

    /** Convenience: parse the JSON string and render. Returns "" on
     *  any parse failure rather than throwing. */
    fun fromStoryJson(contentJson: String): String =
        runCatching { fromStory(json.parseToJsonElement(contentJson) as? JsonArray) }
            .getOrDefault("")

    /** Render a parsed `Story` (verse array) to markdown source. */
    fun fromStory(story: JsonArray?): String {
        if (story == null) return ""
        return story.joinToString("\n\n") { renderVerse(it) }.trim()
    }

    private fun renderVerse(verse: JsonElement): String {
        val obj = verse as? JsonObject ?: return ""
        obj["inline"]?.let { return renderInlines(it as? JsonArray ?: return "") }
        obj["block"]?.let { return renderBlock(it as? JsonObject ?: return "") }
        return ""
    }

    /** Render an inline-span array. Used for top-level `inline` verses
     *  and for nested content inside bold / italic / link / quote. */
    fun renderInlines(spans: JsonArray): String {
        val sb = StringBuilder()
        spans.forEachIndexed { i, span ->
            val piece = renderSpan(span)
            if (piece.isEmpty()) return@forEachIndexed
            // A quote is line-prefixed markdown, so it must own its
            // lines: Tlon happily puts one mid-verse ("before", quote,
            // "after"), which would otherwise render as `before> q…`.
            val quote = isQuote(span)
            if (quote && sb.isNotEmpty() && !sb.endsWith('\n')) sb.append('\n')
            sb.append(piece)
            if (quote && i < spans.lastIndex && !isBreak(spans[i + 1])) sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * Backslash-escape the characters [Markdown.parseInlines] and
     * [MarkdownBlocks.toStory] would otherwise read as markup, so
     * literal text survives an edit or a copy: `a * b`, `~sampel`, a
     * bracketed aside, or a paragraph that happens to start with `#`.
     * Line-start markers (headers, quotes, lists) are escaped only at
     * a line start, everything else wherever it appears.
     */
    internal fun escape(text: String): String {
        if (text.none { it in INLINE_ESCAPES } && !LINE_MARKER.containsMatchIn(text)) return text
        val sb = StringBuilder(text.length + 8)
        text.split('\n').forEachIndexed { li, line ->
            if (li > 0) sb.append('\n')
            val marked = LINE_MARKER.find(line)
            line.forEachIndexed { ci, ch ->
                if (ch in INLINE_ESCAPES || (marked != null && ci == marked.range.first)) sb.append('\\')
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private const val INLINE_ESCAPES = "\\`*_~["
    private val LINE_MARKER = Regex("^\\s*(#{1,6} |> |[-+] |\\d+[.)] )")

    private fun isQuote(span: JsonElement): Boolean =
        (span as? JsonObject)?.let { it.containsKey("blockquote") || it.containsKey("block-quote") } == true

    private fun isBreak(span: JsonElement): Boolean =
        (span as? JsonObject)?.containsKey("break") == true

    private fun renderSpan(span: JsonElement): String {
        if (span is JsonPrimitive && span.isString) return escape(span.content)
        val obj = span as? JsonObject ?: return ""
        when {
            obj.containsKey("bold") -> {
                val inner = renderInlinesOrString(obj["bold"])
                return "**$inner**"
            }
            obj.containsKey("italics") -> {
                val inner = renderInlinesOrString(obj["italics"])
                return "*$inner*"
            }
            obj.containsKey("strike") -> {
                val inner = renderInlinesOrString(obj["strike"])
                return "~~$inner~~"
            }
            obj.containsKey("code") || obj.containsKey("inline-code") -> {
                // Inline code carries either a string or a single-element
                // wrapper; both forms appear in the wild. Tlon spells the
                // span `inline-code`; our parser spells it `code`.
                val node = obj["code"] ?: obj["inline-code"]
                val raw = (node as? JsonPrimitive)?.content
                    ?: renderInlinesOrString(node)
                return "`$raw`"
            }
            obj.containsKey("link") -> {
                val link = obj["link"] as? JsonObject ?: return ""
                val href = (link["href"] as? JsonPrimitive)?.content.orEmpty()
                val label = renderInlinesOrString(link["content"]).ifBlank { href }
                // An autolinked bare URL has label == href; give it back
                // bare, which is what the user typed and what the parser
                // autolinks again.
                return if (label == href) href else "[$label]($href)"
            }
            obj.containsKey("ship") -> {
                return (obj["ship"] as? JsonPrimitive)?.content.orEmpty()
            }
            obj.containsKey("blockquote") || obj.containsKey("block-quote") -> {
                val inner = renderInlinesOrString(obj["blockquote"] ?: obj["block-quote"])
                // Markdown blockquotes are line-prefixed. Split on the
                // round-tripped breaks so multi-line quotes stay quoted.
                // Tlon closes most quotes with a trailing break; dropping
                // the empty lines it makes keeps the editor tidy.
                return inner.split('\n').dropLastWhile { it.isBlank() }
                    .joinToString("\n") { "> $it" }
            }
            obj.containsKey("break") -> return "\n"
            obj.containsKey("task") -> {
                // Checkbox list items: { task: { checked: bool, content: [...] } }
                val task = obj["task"] as? JsonObject ?: return ""
                val checked = (task["checked"] as? JsonPrimitive)
                    ?.content?.toBooleanStrictOrNull() ?: false
                val inner = renderInlinesOrString(task["content"])
                return "${if (checked) "- [x]" else "- [ ]"} $inner"
            }
        }
        return ""
    }

    /** Block-level renderer. */
    private fun renderBlock(block: JsonObject): String {
        when {
            block.containsKey("header") -> {
                val h = block["header"] as? JsonObject ?: return ""
                val tag = (h["tag"] as? JsonPrimitive)?.content ?: "h1"
                val content = renderInlinesOrString(h["content"])
                val hashes = when (tag) {
                    "h1" -> "#"
                    "h2" -> "##"
                    "h3" -> "###"
                    "h4" -> "####"
                    "h5" -> "#####"
                    "h6" -> "######"
                    else -> "#"
                }
                return "$hashes $content"
            }
            block.containsKey("code") -> {
                val c = block["code"] as? JsonObject ?: return ""
                val code = (c["code"] as? JsonPrimitive)?.content.orEmpty()
                val lang = (c["lang"] as? JsonPrimitive)?.content.orEmpty()
                return "```$lang\n$code\n```"
            }
            block.containsKey("rule") -> return "---"
            // A cite has no markdown form. The notebook editor keeps
            // the original block and re-threads it on save; a copy
            // simply omits it.
            block.containsKey("cite") -> return ""
            block.containsKey("block-quote") || block.containsKey("blockquote") -> {
                val q = block["block-quote"] ?: block["blockquote"]
                val inner = when (q) {
                    is JsonObject -> renderInlinesOrString(q["content"] ?: q["inline"])
                    else -> renderInlinesOrString(q)
                }
                return inner.split('\n').dropLastWhile { it.isBlank() }
                    .joinToString("\n") { "> $it" }
            }
            block.containsKey("image") -> {
                val img = block["image"] as? JsonObject ?: return ""
                val src = (img["src"] as? JsonPrimitive)?.content.orEmpty()
                val alt = (img["alt"] as? JsonPrimitive)?.content.orEmpty()
                return "![$alt]($src)"
            }
            block.containsKey("listing") -> {
                // Plain bullet list: { listing: { list: { type: "unordered"|"ordered", items: [...] } } }
                val listing = block["listing"] as? JsonObject ?: return ""
                val list = listing["list"] as? JsonObject ?: return ""
                val type = (list["type"] as? JsonPrimitive)?.content ?: "unordered"
                val items = list["items"] as? JsonArray ?: return ""
                return renderList(list, depth = 0)
            }
        }
        return ""
    }

    /**
     * One list level. Items are `{"item": [inlines]}` (the composer),
     * a bare inline array (older payloads), or a nested `{"list": …}`
     * which renders indented beneath the item before it. A list's own
     * `contents` intro line comes first. Nothing is dropped.
     */
    private fun renderList(list: JsonObject, depth: Int): String {
        val type = (list["type"] as? JsonPrimitive)?.content ?: "unordered"
        val indent = "  ".repeat(depth)
        val out = mutableListOf<String>()
        renderInlinesOrString(list["contents"]).takeIf { it.isNotBlank() }?.let { out += indent + it }
        var n = 0
        for (item in list["items"] as? JsonArray ?: JsonArray(emptyList())) {
            val nested = (item as? JsonObject)?.get("list") as? JsonObject
            if (nested != null) {
                out += renderList(nested, depth + 1)
                continue
            }
            n++
            val prefix = if (type == "ordered") "$n." else "-"
            val inlines = (item as? JsonObject)?.get("item") ?: item
            out += "$indent$prefix ${renderInlinesOrString(inlines)}"
        }
        return out.joinToString("\n")
    }

    /** Some payloads use a JSON string in places we'd otherwise expect
     *  an inline array (legacy or compact form). Accept both. */
    private fun renderInlinesOrString(node: JsonElement?): String = when (node) {
        null -> ""
        is JsonArray -> renderInlines(node)
        is JsonPrimitive -> if (node.isString) node.content else ""
        else -> ""
    }
}
