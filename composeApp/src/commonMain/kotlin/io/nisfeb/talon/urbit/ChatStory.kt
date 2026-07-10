package io.nisfeb.talon.urbit

import kotlinx.serialization.json.Json
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
 * - Triple-backtick fenced blocks become a code-block verse via
 *   [codeBlockVerse]. An optional language tag after the opening
 *   ``` ``` `` is preserved (e.g. ```` ```kotlin ````). An unclosed
 *   fence falls back to inline so the rest of the message still renders.
 * - Inline tokens inside each line (bold, italic, code, links, patps)
 *   go through [Markdown.parseInlines].
 *
 * Intentionally does NOT parse headings or horizontal rules — those
 * live in [MarkdownBlocks.toStory] for notebook composition. Chat
 * keeps a leaner surface so a plain `# hello` doesn't surprise users
 * with a header.
 */
/**
 * Editing a post round-trips its content through the plain-text editor,
 * but only *some* of a Story survives that trip. Text verses do. Code
 * blocks do — the ``` fences carry them. A cite (the quoted post), an
 * image, or a server-enriched link preview does NOT: it flattens to a
 * label like "~zod: hello" and gets re-posted as meaningless literal
 * text, destroying the reference.
 *
 * So the editor edits the text and these blocks pass through untouched.
 * [editableText] is what the dialog shows; [editedStory] rebuilds the
 * content on save, keeping each preserved block where it sat relative
 * to the text.
 */
private fun isPreservedBlock(verse: JsonObject): Boolean {
    val block = verse["block"] as? JsonObject ?: return false
    // Code is the one block the text form represents faithfully.
    return !block.containsKey("code")
}

private fun versesOf(contentJson: String): List<JsonObject>? =
    runCatching {
        (STORY_JSON.parseToJsonElement(contentJson) as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
    }.getOrNull()

/** The text an edit dialog should open with: everything except the
 *  blocks that can't survive a text round-trip (cites, images, links).
 *  Falls back to the whole story's plain text if the content won't
 *  parse — same as the pre-existing behaviour. */
fun editableText(contentJson: String): String {
    val verses = versesOf(contentJson)
        ?: return Story.plainText(runCatching { STORY_JSON.parseToJsonElement(contentJson) }.getOrNull())
    val editable = verses.filterNot(::isPreservedBlock)
    return Story.plainText(buildJsonArray { editable.forEach { add(it) } })
}

/**
 * Rebuild a post's content after an edit: [newText] re-parsed, with the
 * original's preserved blocks threaded back in at their original
 * position relative to the text (a quote above the message stays above
 * it). When the original had no text at all, the new text lands last.
 */
fun editedStory(contentJson: String, newText: String): JsonArray {
    val verses = versesOf(contentJson) ?: return chatTextToStory(newText)
    val preserved = verses.filter(::isPreservedBlock)
    if (preserved.isEmpty()) return chatTextToStory(newText)

    val newVerses = chatTextToStory(newText)
    return buildJsonArray {
        var textEmitted = false
        for (verse in verses) {
            if (isPreservedBlock(verse)) {
                add(verse)
            } else if (!textEmitted) {
                // First text/code verse: the whole edited body goes here,
                // and the original's remaining text verses are dropped.
                newVerses.forEach { add(it) }
                textEmitted = true
            }
        }
        if (!textEmitted) newVerses.forEach { add(it) }
    }
}

private val STORY_JSON = Json { ignoreUnknownKeys = true }

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
            line.startsWith("```") -> {
                // Scan ahead for the closing fence. If there isn't one,
                // don't eat the rest of the message — fall through to
                // inline so the opener renders as literal text and the
                // body still ships.
                val close = (i + 1 until lines.size)
                    .firstOrNull { lines[it].startsWith("```") }
                if (close == null) {
                    if (pending.isNotEmpty()) {
                        pending += buildJsonObject { put("break", JsonNull) }
                    }
                    Markdown.parseInlines(line).forEach { pending += it }
                    i++
                } else {
                    flushParagraph()
                    val lang = line.removePrefix("```").trim()
                    val body = lines.subList(i + 1, close).joinToString("\n")
                    verses += codeBlockVerse(body, lang)
                    i = close + 1
                }
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
