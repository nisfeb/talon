package io.nisfeb.talon.urbit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Markdown → Tlon Story (`Verse[]`) for notebook composing. Handles
 * the block-level chunks a notebook user actually writes: paragraphs,
 * headings (#/##/###), fenced code blocks, and blockquotes. Inline
 * styles within a paragraph are delegated to the existing [Markdown]
 * parser so formatting is consistent between chat and notebook.
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
                else -> {
                    if (buf.isNotEmpty()) buf.append('\n')
                    buf.append(line)
                    i++
                }
            }
        }
        flushParagraph()
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

    private fun codeBlock(code: String, lang: String) = buildJsonObject {
        put("block", buildJsonObject {
            put("code", buildJsonObject {
                put("code", code)
                put("lang", lang)
            })
        })
    }
}
