package io.nisfeb.talon.urbit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Lightweight composer → Story converter used by chat sends (DMs,
 * clubs, %channels chat, and notebook/gallery comments).
 *
 * Behaviour:
 * - Each line becomes a verse with `{inline: [...]}`. Non-last lines
 *   append a `{break: null}` after their content to preserve
 *   composer-side vertical spacing.
 * - Consecutive lines beginning with `> ` are grouped into a single
 *   verse whose content is a `{blockquote: [...]}` inline, matching
 *   Tlon's rendering for quoted replies.
 * - Inline tokens inside each line (bold, italic, code, links, patps)
 *   go through [Markdown.parseInlines].
 *
 * Intentionally does NOT parse headings, fenced code, or horizontal
 * rules — those live in [MarkdownBlocks.toStory] for notebook
 * composition. Chat keeps a leaner surface so a plain `# hello`
 * doesn't surprise users with a header.
 */
internal fun chatTextToStory(text: String): JsonArray = buildJsonArray {
    val lines = text.split('\n')
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.startsWith("> ")) {
            // Collect every consecutive `> `-prefixed line into one quote.
            val quoted = mutableListOf<String>()
            while (i < lines.size && lines[i].startsWith("> ")) {
                quoted += lines[i].removePrefix("> ")
                i++
            }
            val inner = buildJsonArray {
                quoted.forEachIndexed { idx, q ->
                    Markdown.parseInlines(q).forEach { add(it) }
                    if (idx < quoted.lastIndex) {
                        add(buildJsonObject { put("break", JsonNull) })
                    }
                }
            }
            add(buildJsonObject {
                put("inline", buildJsonArray {
                    add(buildJsonObject { put("blockquote", inner) })
                })
            })
        } else {
            val parsed = Markdown.parseInlines(line)
            val isLast = i == lines.lastIndex
            val inline = if (isLast) parsed else buildJsonArray {
                parsed.forEach { add(it) }
                add(buildJsonObject { put("break", JsonNull) })
            }
            add(buildJsonObject { put("inline", inline) })
            i++
        }
    }
}
