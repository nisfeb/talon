package io.nisfeb.talon.urbit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Serialize a Tlon message story (the `contentJson` verse array) to
 * gemtext, for publishing to Lattice. Gemtext is line-oriented: links
 * live on their own `=> url label` lines, so inline links are pulled
 * out of the prose and appended after the paragraph they appeared in.
 * Inline emphasis (bold/italic) flattens to plain text — gemtext has
 * no inline styling.
 */
object StoryToGemtext {

    private val json = Json { ignoreUnknownKeys = true }

    /** One message's body as gemtext, or "" on a parse failure. */
    fun fromStoryJson(contentJson: String): String =
        runCatching {
            val story = json.parseToJsonElement(contentJson) as? JsonArray ?: return ""
            story.joinToString("\n\n") { verse(it) }.trim()
        }.getOrDefault("")

    /** A whole thread: a title heading, then each message under a
     *  byline heading. [times] and [authors] parallel [bodies]. */
    fun thread(
        title: String,
        entries: List<Entry>,
    ): String = buildString {
        append("# ").append(title).append("\n")
        for (e in entries) {
            append("\n## ").append(e.byline).append("\n\n")
            append(fromStoryJson(e.contentJson))
            append("\n")
        }
    }.trim() + "\n"

    data class Entry(val byline: String, val contentJson: String)

    private fun verse(v: JsonElement): String {
        val obj = v as? JsonObject ?: return ""
        (obj["inline"] as? JsonArray)?.let { return inlines(it) }
        (obj["block"] as? JsonObject)?.let { return block(it) }
        return ""
    }

    /** An inline span array → a paragraph plus trailing `=> ` links. */
    private fun inlines(spans: JsonArray): String {
        val prose = StringBuilder()
        val links = mutableListOf<String>()
        for (span in spans) collectSpan(span, prose, links)
        val text = prose.toString().trim()
        return buildString {
            if (text.isNotEmpty()) append(text)
            for (link in links) {
                if (isNotEmpty()) append("\n")
                append(link)
            }
        }
    }

    private fun collectSpan(span: JsonElement, prose: StringBuilder, links: MutableList<String>) {
        if (span is JsonPrimitive && span.isString) {
            prose.append(span.content)
            return
        }
        val obj = span as? JsonObject ?: return
        when {
            obj.containsKey("bold") -> flatten(obj["bold"], prose, links)
            obj.containsKey("italics") -> flatten(obj["italics"], prose, links)
            obj.containsKey("strike") -> flatten(obj["strike"], prose, links)
            obj.containsKey("blockquote") -> flatten(obj["blockquote"], prose, links)
            obj.containsKey("code") -> {
                val raw = (obj["code"] as? JsonPrimitive)?.content ?: flat(obj["code"])
                prose.append(raw)
            }
            obj.containsKey("ship") -> prose.append((obj["ship"] as? JsonPrimitive)?.content.orEmpty())
            obj.containsKey("link") -> {
                val link = obj["link"] as? JsonObject ?: return
                val href = (link["href"] as? JsonPrimitive)?.content.orEmpty()
                val label = flat(link["content"]).ifBlank { href }
                if (href.isNotEmpty()) {
                    prose.append(label)
                    links.add("=> $href $label")
                }
            }
            obj.containsKey("break") -> prose.append("\n")
        }
    }

    private fun flatten(el: JsonElement?, prose: StringBuilder, links: MutableList<String>) {
        when (el) {
            is JsonArray -> for (s in el) collectSpan(s, prose, links)
            is JsonPrimitive -> if (el.isString) prose.append(el.content)
            else -> {}
        }
    }

    /** Flatten inline content to bare text (for a link label). */
    private fun flat(el: JsonElement?): String {
        val sb = StringBuilder()
        flatten(el, sb, mutableListOf())
        return sb.toString().trim()
    }

    private fun block(obj: JsonObject): String {
        (obj["header"] as? JsonObject)?.let { h ->
            val level = (h["tag"] as? JsonPrimitive)?.content ?: "h2"
            val hashes = "#".repeat((level.removePrefix("h").toIntOrNull() ?: 2).coerceIn(1, 3))
            return "$hashes ${flat(h["content"])}"
        }
        (obj["code"] as? JsonObject)?.let { c ->
            val code = (c["code"] as? JsonPrimitive)?.content ?: flat(c["code"])
            return "```\n$code\n```"
        }
        (obj["image"] as? JsonObject)?.let { img ->
            val src = (img["src"] as? JsonPrimitive)?.content.orEmpty()
            return if (src.isNotEmpty()) "=> $src (image)" else ""
        }
        (obj["listing"] as? JsonObject)?.let { return flat(it["list"]) }
        return ""
    }
}
