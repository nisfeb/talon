package io.nisfeb.talon.urbit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Lightweight markdown → Tlon inline-span converter. Handles the chunks
 * that show up in real-world chat (**bold**, *italic* / _italic_,
 * `code`, [label](url), ~patp) without pulling in a full markdown
 * parser. Anything we don't recognize passes through as plain text so
 * no content is ever lost.
 *
 * Returns a list of inline spans suitable for plugging into a Story
 * verse's `inline` array.
 */
object Markdown {

    /** Parse one line/paragraph into inline spans. */
    fun parseInlines(text: String): JsonArray = buildJsonArray {
        val tokens = tokenize(text)
        tokens.forEach { add(emit(it)) }
    }

    private fun emit(token: Token): JsonElement = when (token) {
        is Token.Plain -> JsonPrimitive(token.text)
        is Token.Bold -> buildJsonObject {
            put("bold", buildJsonArray { token.inner.forEach { add(emit(it)) } })
        }
        is Token.Italic -> buildJsonObject {
            put("italics", buildJsonArray { token.inner.forEach { add(emit(it)) } })
        }
        is Token.Strike -> buildJsonObject {
            put("strike", buildJsonArray { token.inner.forEach { add(emit(it)) } })
        }
        is Token.Code -> buildJsonObject { put("code", token.text) }
        is Token.Link -> buildJsonObject {
            put("link", buildJsonObject {
                put("href", token.href)
                put("content", token.label)
            })
        }
        is Token.Ship -> buildJsonObject { put("ship", token.patp) }
    }

    // ───────── tokenizer ─────────

    private sealed interface Token {
        data class Plain(val text: String) : Token
        data class Bold(val inner: List<Token>) : Token
        data class Italic(val inner: List<Token>) : Token
        data class Strike(val inner: List<Token>) : Token
        data class Code(val text: String) : Token
        data class Link(val label: String, val href: String) : Token
        data class Ship(val patp: String) : Token
    }

    private fun tokenize(text: String): List<Token> {
        val out = mutableListOf<Token>()
        var i = 0
        val len = text.length
        val plain = StringBuilder()

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                out.add(Token.Plain(plain.toString()))
                plain.clear()
            }
        }

        while (i < len) {
            val c = text[i]

            // Inline code: `text`
            if (c == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    flushPlain()
                    out.add(Token.Code(text.substring(i + 1, end)))
                    i = end + 1
                    continue
                }
            }

            // Link: [label](url)
            if (c == '[') {
                val closeBracket = text.indexOf(']', i + 1)
                if (closeBracket > i && closeBracket + 1 < len && text[closeBracket + 1] == '(') {
                    val closeParen = text.indexOf(')', closeBracket + 2)
                    if (closeParen > closeBracket + 1) {
                        val label = text.substring(i + 1, closeBracket)
                        val href = text.substring(closeBracket + 2, closeParen)
                        flushPlain()
                        out.add(Token.Link(label = label, href = href))
                        i = closeParen + 1
                        continue
                    }
                }
            }

            // Bold: **text** (greedy match closing **)
            if (c == '*' && i + 1 < len && text[i + 1] == '*') {
                val end = text.indexOf("**", i + 2)
                if (end > i + 1) {
                    flushPlain()
                    out.add(Token.Bold(tokenize(text.substring(i + 2, end))))
                    i = end + 2
                    continue
                }
            }

            // Italic: *text* or _text_
            if ((c == '*' || c == '_') && i + 1 < len && text[i + 1] != c) {
                val end = text.indexOf(c, i + 1)
                if (end > i && !isWordChar(text.getOrNull(end + 1))) {
                    flushPlain()
                    out.add(Token.Italic(tokenize(text.substring(i + 1, end))))
                    i = end + 1
                    continue
                }
            }

            // Strikethrough: ~~text~~
            if (c == '~' && i + 1 < len && text[i + 1] == '~') {
                val end = text.indexOf("~~", i + 2)
                if (end > i + 1) {
                    flushPlain()
                    out.add(Token.Strike(tokenize(text.substring(i + 2, end))))
                    i = end + 2
                    continue
                }
            }

            // ~patp mention — reuse the shared regex for consistency.
            if (c == '~' && (i == 0 || !isPatpChar(text[i - 1]))) {
                val rest = text.substring(i)
                val match = PATP_REGEX.find(rest)
                if (match != null && match.range.first == 0) {
                    val end = i + match.range.last + 1
                    val after = text.getOrNull(end)
                    if (after == null || !isPatpChar(after)) {
                        flushPlain()
                        out.add(Token.Ship(match.value))
                        i = end
                        continue
                    }
                }
            }

            plain.append(c)
            i++
        }
        flushPlain()
        return out
    }

    private fun isWordChar(c: Char?): Boolean =
        c != null && (c.isLetterOrDigit() || c == '_')

    private fun isPatpChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '-'
}
