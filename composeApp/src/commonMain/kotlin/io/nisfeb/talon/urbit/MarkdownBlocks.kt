package io.nisfeb.talon.urbit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Markdown → Tlon Story (`Verse[]`) for notebook composing. Handles
 * the block-level chunks a notebook user actually writes: paragraphs,
 * headings (#/##/###), fenced code blocks, blockquotes, horizontal
 * rules, and bullet/numbered lists. Inline styles within a paragraph
 * are delegated to the existing [Markdown] parser so formatting is
 * consistent between chat and notebook.
 *
 * Blocks we don't recognise fall through as plain paragraphs so no
 * content is dropped — the worst case is loss of styling, never loss
 * of text.
 */
object MarkdownBlocks {

    /**
     * [tables] false is the ship's form: %channels has no table block and
     * refuses a post holding one, so a table goes as its own lines, which
     * [Story] draws as a table again on the way in. True is for drawing.
     */
    fun toStory(text: String, tables: Boolean = true): JsonArray = buildJsonArray {
        val lines = text.replace("\r\n", "\n").split('\n')
        var i = 0
        val buf = StringBuilder()
        fun flushParagraph() {
            val s = buf.toString().trim()
            buf.clear()
            if (s.isEmpty()) return
            add(inlineVerse(s))
        }
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.isBlank() -> { flushParagraph(); i++ }
                line.startsWith("```") -> {
                    // Scan ahead for the closing fence. If there isn't
                    // one, don't eat the rest of the body — treat this
                    // `` ``` `` like a plain paragraph so the author's
                    // later text still renders.
                    val close = (i + 1 until lines.size)
                        .firstOrNull { lines[it].startsWith("```") }
                    if (close == null) {
                        if (buf.isNotEmpty()) buf.append('\n')
                        buf.append(line)
                        i++
                    } else {
                        flushParagraph()
                        val lang = line.removePrefix("```").trim()
                        val body = lines.subList(i + 1, close).joinToString("\n")
                        add(codeBlock(body, lang))
                        i = close + 1
                    }
                }
                HEADER_RE.matches(line) -> {
                    flushParagraph()
                    val m = HEADER_RE.find(line)!!
                    add(headerBlock("h${m.groupValues[1].length}", m.groupValues[2]))
                    i++
                }
                IMAGE_RE.matches(line) -> {
                    // `![alt](src)` on its own line: the shape RawMarkdown
                    // gives a Tlon image block. Dimensions are restored
                    // from the original post on edit (mergeEdit); a new
                    // image is posted without them.
                    flushParagraph()
                    val m = IMAGE_RE.find(line)!!
                    add(imageBlock(src = m.groupValues[2], alt = m.groupValues[1]))
                    i++
                }
                line.startsWith("> ") -> {
                    flushParagraph()
                    val quoted = StringBuilder()
                    while (i < lines.size && lines[i].startsWith("> ")) {
                        if (quoted.isNotEmpty()) quoted.append('\n')
                        quoted.append(lines[i].removePrefix("> "))
                        i++
                    }
                    // Urbit's blockquote is an inline wrapper, not a
                    // block — emit as an inline verse containing a
                    // blockquote span around the parsed inlines.
                    add(buildJsonObject {
                        put("inline", buildJsonArray {
                            add(buildJsonObject {
                                put("blockquote", Markdown.parseInlines(quoted.toString()))
                            })
                        })
                    })
                }
                line.startsWith("---") || line.startsWith("***") -> {
                    flushParagraph()
                    add(buildJsonObject {
                        put("block", buildJsonObject { put("rule", JsonNull) })
                    })
                    i++
                }
                line.contains('|') && i + 1 < lines.size && isTableSeparator(lines[i + 1]) -> {
                    // GFM table: a `| a | b |` header, a `| --- | --- |`
                    // separator, then rows until a blank / non-pipe line.
                    // Tlon has no table block, so for drawing we emit a
                    // bespoke one ([Story] renders it as a real grid). Cells
                    // go through the inline parser so styling survives.
                    flushParagraph()
                    var end = i + 2
                    while (end < lines.size && lines[end].isNotBlank() &&
                        lines[end].contains('|') && !lines[end].startsWith("```")
                    ) end++
                    if (tables) {
                        add(tableBlock(splitTableCells(line), (i + 2 until end).map { splitTableCells(lines[it]) }))
                    } else {
                        add(inlineVerse(lines.subList(i, end).joinToString("\n")))
                    }
                    i = end
                }
                isListLine(line) -> {
                    // One `block.listing` per run of list lines; a top-level
                    // item of the other kind (bullets, then numbers) starts a
                    // fresh list. Deeper items nest, as Lattice nests them.
                    flushParagraph()
                    val ordered = ORDERED_LIST_RE.matches(line)
                    var end = i + 1
                    while (end < lines.size && isListLine(lines[end]) &&
                        (listDepth(lines[end]) > 1 || ORDERED_LIST_RE.matches(lines[end]) == ordered)
                    ) end++
                    add(buildJsonObject {
                        put("block", buildJsonObject { put("listing", listingOf(lines.subList(i, end))) })
                    })
                    i = end
                }
                else -> {
                    if (buf.isNotEmpty()) buf.append('\n')
                    buf.append(line)
                    i++
                }
            }
        }
        flushParagraph()
    }

    /**
     * A list item's level, 1 at the margin: Lattice's rule (59-md.js), two
     * spaces a level and a tab as four spaces, so a note reads the same in
     * both. [RawMarkdown] writes nested items two spaces a level.
     */
    private fun listDepth(line: String): Int =
        line.takeWhile { it == ' ' || it == '\t' }.replace("\t", "    ").length / 2 + 1

    private class ListLevel(val ordered: Boolean) {
        val items = ArrayList<JsonObject>()
        fun json(): JsonObject = buildJsonObject {
            put("list", buildJsonObject {
                put("type", if (ordered) "ordered" else "unordered")
                put("items", JsonArray(items))
                // Required by the ship's parser, empty or not, at every level.
                put("contents", buildJsonArray {})
            })
        }
    }

    /**
     * A run of list lines as one listing, deeper items as a list inside
     * the item above them, the recursive shape Tlon's listing has and
     * [Story] draws. At the same depth, the other kind of marker ends
     * that sub-list and starts one of its own kind.
     */
    private fun listingOf(run: List<String>): JsonObject {
        val stack = ArrayList<ListLevel>()
        fun close() {
            val done = stack.removeAt(stack.lastIndex)
            stack.last().items += done.json()
        }
        for (line in run) {
            val depth = listDepth(line)
            val ordered = ORDERED_LIST_RE.matches(line)
            while (stack.size > depth) close()
            if (stack.size == depth && depth > 1 && stack.last().ordered != ordered) close()
            while (stack.size < depth) stack += ListLevel(ordered)
            stack.last().items += buildJsonObject { put("item", Markdown.parseInlines(stripListMarker(line))) }
        }
        while (stack.size > 1) close()
        return stack.single().json()
    }

    // `- `, `* `, `+ ` bullets and `1.`/`1)` numbered, each needing at
    // least one space and a non-blank item body. The body guard keeps a
    // bare `* ` (or a `**bold**` line, which starts with `*` but not
    // `* `) from being read as a list.
    // Leading spaces are allowed: they are the nesting ([listDepth]).
    private val UNORDERED_LIST_RE = Regex("^\\s*[-*+] +\\S.*")
    private val ORDERED_LIST_RE = Regex("^\\s*\\d+[.)] +\\S.*")
    private val HEADER_RE = Regex("^(#{1,6}) (.*)")
    private val IMAGE_RE = Regex("^!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)\\s*$")

    /** True if [line] opens a bullet or numbered list item. Shared with
     *  the render-time markdown detector in [Story]. */
    internal fun isListLine(line: String): Boolean =
        UNORDERED_LIST_RE.matches(line) || ORDERED_LIST_RE.matches(line)

    private fun stripListMarker(line: String): String = when {
        ORDERED_LIST_RE.matches(line) -> line.replaceFirst(Regex("^\\s*\\d+[.)] +"), "")
        else -> line.replaceFirst(Regex("^\\s*[-*+] +"), "")
    }

    private fun imageBlock(src: String, alt: String) = buildJsonObject {
        put("block", buildJsonObject {
            put("image", buildJsonObject {
                put("src", src)
                put("width", 0)
                put("height", 0)
                put("alt", alt)
            })
        })
    }

    /**
     * Rebuild an edited post from the re-parsed markdown plus what the
     * text form could not carry, read from the post as it was:
     *  - cite blocks (no markdown form) come back at their original
     *    position relative to the text, the way chat's editedStory
     *    keeps a quote above its message;
     *  - an image the editor round-tripped as `![alt](src)` gets its
     *    original width/height back, matched by src.
     * With no prior content this is the parsed story unchanged.
     */
    fun mergeEdit(prior: JsonArray?, parsed: JsonArray): JsonArray {
        if (prior == null) return parsed
        val priorImages = prior.mapNotNull { v ->
            ((v as? JsonObject)?.get("block") as? JsonObject)?.get("image") as? JsonObject
        }.associateBy { (it["src"] as? JsonPrimitive)?.content.orEmpty() }
        val withDims = buildJsonArray {
            parsed.forEach { v ->
                val img = ((v as? JsonObject)?.get("block") as? JsonObject)?.get("image") as? JsonObject
                val src = (img?.get("src") as? JsonPrimitive)?.content
                val original = src?.let { priorImages[it] }
                if (img != null && original != null && (img["width"] as? JsonPrimitive)?.content == "0") {
                    add(buildJsonObject { put("block", buildJsonObject { put("image", original) }) })
                } else {
                    add(v)
                }
            }
        }
        fun isCite(v: JsonElement) = ((v as? JsonObject)?.get("block") as? JsonObject)?.containsKey("cite") == true
        if (prior.none(::isCite)) return withDims
        return buildJsonArray {
            var textEmitted = false
            for (v in prior) {
                if (isCite(v)) {
                    add(v)
                } else if (!textEmitted) {
                    withDims.forEach { add(it) }
                    textEmitted = true
                }
            }
            if (!textEmitted) withDims.forEach { add(it) }
        }
    }

    // A GFM table separator: only pipes, dashes, optional alignment
    // colons and spaces, and at least one pipe (so a bare `---` stays a
    // horizontal rule rather than a one-column table).
    private val TABLE_SEP_RE = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")

    /** True if [line] is the `| --- | --- |` row under a table header.
     *  Shared with the render-time markdown detector in [Story]. */
    internal fun isTableSeparator(line: String): Boolean =
        line.contains('|') && line.contains('-') && TABLE_SEP_RE.matches(line)

    /** Split a `| a | b |` row into trimmed cells, tolerating missing
     *  outer pipes (`a | b`). */
    private fun splitTableCells(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|")) s = s.dropLast(1)
        return s.split("|").map { it.trim() }
    }

    /** Bespoke `block.table` verse: `header` is one inline array per
     *  column; `rows` is a list of the same. Cells are inline-parsed so
     *  styling inside a cell renders. [Story] draws it as a grid. */
    private fun tableBlock(header: List<String>, rows: List<List<String>>) = buildJsonObject {
        put("block", buildJsonObject {
            put("table", buildJsonObject {
                put("header", buildJsonArray { header.forEach { add(Markdown.parseInlines(it)) } })
                put("rows", buildJsonArray {
                    rows.forEach { row ->
                        add(buildJsonArray { row.forEach { add(Markdown.parseInlines(it)) } })
                    }
                })
            })
        })
    }

    private fun inlineVerse(text: String) = buildJsonObject {
        put("inline", Markdown.parseInlines(text))
    }

    private fun headerBlock(tag: String, text: String) = buildJsonObject {
        put("block", buildJsonObject {
            put("header", buildJsonObject {
                put("tag", tag)
                put("content", Markdown.parseInlines(text))
            })
        })
    }

    private fun codeBlock(code: String, lang: String) = codeBlockVerse(code, lang)
}

/**
 * Story verse for a fenced code block. Shared by [MarkdownBlocks]
 * (notebook composer) and [chatTextToStory] (chat / DM / club / channel
 * composer) so the wire shape stays in one place.
 *
 * `lang` is normalized to a valid Hoon `@tas` term — lowercase, only
 * `[a-z0-9-]`, defaulting to `text` when empty. The %channels agent
 * runs `(se %tas)` on the value during dejs and NACKs the poke
 * (`poke-as cast fail [%key 'lang']`) on `""` or any non-term input.
 * Mirrors tlon-apps' mdast→story emitter (packages/api/src/client/
 * markdown/mdastToStory.ts).
 */
internal fun codeBlockVerse(code: String, lang: String) = buildJsonObject {
    put("block", buildJsonObject {
        put("code", buildJsonObject {
            put("code", code)
            put("lang", normalizeCodeLang(lang))
        })
    })
}

private val codeLangSanitizer = Regex("[^a-z0-9-]")

internal fun normalizeCodeLang(lang: String): String {
    // A @tas starts with a letter: `6502` or `-x` was refused as it stood.
    val cleaned = lang.trim().lowercase().replace(codeLangSanitizer, "").dropWhile { it !in 'a'..'z' }
    return cleaned.ifEmpty { "text" }
}
