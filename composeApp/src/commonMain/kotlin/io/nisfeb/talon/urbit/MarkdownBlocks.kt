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

    fun toStory(text: String): JsonArray = buildJsonArray {
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
                    // Tlon has no table block, so we emit a bespoke one
                    // ([Story] renders it as a real grid). Cells go through
                    // the inline parser so styling inside a cell survives.
                    flushParagraph()
                    val header = splitTableCells(line)
                    i += 2
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].isNotBlank() &&
                        lines[i].contains('|') && !lines[i].startsWith("```")
                    ) {
                        rows.add(splitTableCells(lines[i]))
                        i++
                    }
                    add(tableBlock(header, rows))
                }
                isListLine(line) -> {
                    // Group consecutive same-kind list lines (all bullet
                    // or all numbered) into one `block.listing`. Mixing
                    // markers starts a fresh list. Flat only — nested
                    // indentation is rare in the content this handles and
                    // the renderer flattens anyway.
                    flushParagraph()
                    val ordered = ORDERED_LIST_RE.matches(line)
                    add(buildJsonObject {
                        put("block", buildJsonObject {
                            put("listing", buildJsonObject {
                                put("list", buildJsonObject {
                                    put("type", if (ordered) "ordered" else "unordered")
                                    put("items", buildJsonArray {
                                        while (i < lines.size && isListLine(lines[i]) &&
                                            ORDERED_LIST_RE.matches(lines[i]) == ordered
                                        ) {
                                            add(buildJsonObject {
                                                put("item", Markdown.parseInlines(stripListMarker(lines[i])))
                                            })
                                            i++
                                        }
                                    })
                                })
                            })
                        })
                    })
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

    // `- `, `* `, `+ ` bullets and `1.`/`1)` numbered, each needing at
    // least one space and a non-blank item body. The body guard keeps a
    // bare `* ` (or a `**bold**` line, which starts with `*` but not
    // `* `) from being read as a list.
    // Leading spaces are allowed so a nested list RawMarkdown indents
    // still parses; the composer keeps one flat list, so the nesting
    // itself is not kept, only the items.
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
    val cleaned = lang.trim().lowercase().replace(codeLangSanitizer, "")
    return cleaned.ifEmpty { "text" }
}
