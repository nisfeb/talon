package io.nisfeb.talon.urbit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Lightweight composer → Story converter used by chat sends (DMs,
 * clubs, %channels chat, and notebook/gallery comments).
 *
 * Behaviour:
 * - Consecutive non-empty non-quoted lines collapse into a single
 *   verse with `{break: null}` inlines between them. A `break` is a
 *   soft line break (line-height spacing); a blank line in the
 *   composer ends the verse so the next line starts a fresh verse
 *   (paragraph-level gap from StoryRenderer's vertical arrangement).
 *   Earlier revisions split every line into its own verse, which
 *   stacked the trailing `\n` on top of the column gap and rendered
 *   as visible double-spacing.
 * - Consecutive lines beginning with `> ` are grouped into a single
 *   blockquote verse, matching Tlon's rendering for quoted replies.
 * - Inline tokens inside each line (bold, italic, code, links, patps)
 *   go through [Markdown.parseInlines].
 *
 * Intentionally does NOT parse headings, fenced code, or horizontal
 * rules — those live in [MarkdownBlocks.toStory] for notebook
 * composition. Chat keeps a leaner surface so a plain `# hello`
 * doesn't surprise users with a header.
 */
internal fun chatTextToStory(text: String): JsonArray {
    val lines = text.split('\n')
    val verses = mutableListOf<JsonObject>()
    val pending = mutableListOf<JsonElement>()

    fun flushParagraph() {
        if (pending.isEmpty()) return
        // Strip a trailing `break` if present — defensive; the loop
        // below shouldn't leave one, but this guarantees a clean tail.
        while (pending.lastOrNull()
                ?.let { (it as? JsonObject)?.containsKey("break") } == true
        ) {
            pending.removeAt(pending.lastIndex)
        }
        if (pending.isNotEmpty()) {
            verses += buildJsonObject {
                put("inline", buildJsonArray { pending.forEach { add(it) } })
            }
        }
        pending.clear()
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.isEmpty() -> {
                // Blank line = paragraph break. Flush the buffered
                // verse and skip the empty line itself.
                flushParagraph()
                i++
            }
            line.startsWith("> ") -> {
                flushParagraph()
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
                verses += buildJsonObject {
                    put("inline", buildJsonArray {
                        add(buildJsonObject { put("blockquote", inner) })
                    })
                }
            }
            else -> {
                if (pending.isNotEmpty()) {
                    pending += buildJsonObject { put("break", JsonNull) }
                }
                Markdown.parseInlines(line).forEach { pending += it }
                i++
            }
        }
    }
    flushParagraph()

    // Preserve the historical contract that an empty input still
    // produces a verse — UIs gate empty sends elsewhere.
    if (verses.isEmpty()) {
        verses += buildJsonObject { put("inline", buildJsonArray { }) }
    }

    return buildJsonArray { verses.forEach { add(it) } }
}
