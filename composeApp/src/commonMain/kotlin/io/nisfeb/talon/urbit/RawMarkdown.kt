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
        for (span in spans) {
            sb.append(renderSpan(span))
        }
        return sb.toString()
    }

    private fun renderSpan(span: JsonElement): String {
        if (span is JsonPrimitive && span.isString) return span.content
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
            obj.containsKey("code") -> {
                // Inline code carries either a string or a single-element
                // wrapper; both forms appear in the wild.
                val raw = (obj["code"] as? JsonPrimitive)?.content
                    ?: renderInlinesOrString(obj["code"])
                return "`$raw`"
            }
            obj.containsKey("link") -> {
                val link = obj["link"] as? JsonObject ?: return ""
                val href = (link["href"] as? JsonPrimitive)?.content.orEmpty()
                val label = renderInlinesOrString(link["content"]).ifBlank { href }
                return "[$label]($href)"
            }
            obj.containsKey("ship") -> {
                return (obj["ship"] as? JsonPrimitive)?.content.orEmpty()
            }
            obj.containsKey("blockquote") -> {
                val inner = renderInlinesOrString(obj["blockquote"])
                // Markdown blockquotes are line-prefixed. Split on the
                // round-tripped breaks so multi-line quotes stay quoted.
                return inner.split('\n').joinToString("\n") { "> $it" }
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
                return items.mapIndexed { i, item ->
                    val prefix = if (type == "ordered") "${i + 1}." else "-"
                    "$prefix ${renderInlinesOrString(item)}"
                }.joinToString("\n")
            }
        }
        return ""
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
