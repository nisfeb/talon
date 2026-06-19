package io.nisfeb.talon.urbit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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
                line.startsWith("### ") -> {
                    flushParagraph()
                    add(headerBlock("h3", line.removePrefix("### ")))
                    i++
                }
                line.startsWith("## ") -> {
                    flushParagraph()
                    add(headerBlock("h2", line.removePrefix("## ")))
                    i++
                }
                line.startsWith("# ") -> {
                    flushParagraph()
                    add(headerBlock("h1", line.removePrefix("# ")))
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
    private val UNORDERED_LIST_RE = Regex("^[-*+] +\\S.*")
    private val ORDERED_LIST_RE = Regex("^\\d+[.)] +\\S.*")

    /** True if [line] opens a bullet or numbered list item. Shared with
     *  the render-time markdown detector in [Story]. */
    internal fun isListLine(line: String): Boolean =
        UNORDERED_LIST_RE.matches(line) || ORDERED_LIST_RE.matches(line)

    private fun stripListMarker(line: String): String = when {
        ORDERED_LIST_RE.matches(line) -> line.replaceFirst(Regex("^\\d+[.)] +"), "")
        else -> line.replaceFirst(Regex("^[-*+] +"), "")
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
