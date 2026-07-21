package io.nisfeb.talon.urbit

/**
 * Markdown → HTML for %notes clear-web publishing.
 *
 * The output is served unauthenticated from the user's own ship at
 * `/notes/pub/...`, so this is a trust boundary, not a formatting
 * convenience. Two rules follow from that:
 *
 *  - every scrap of user text is escaped, and tags only ever come from
 *    this file. Raw HTML in the source is escaped rather than passed
 *    through, so a note can't inject script into a page served from the
 *    author's domain.
 *  - link targets are scheme-checked. `[x](javascript:…)` is the
 *    obvious hole here and is dropped to a plain `#`.
 *
 * Deliberately small: headings, paragraphs, bullet/ordered lists, code
 * fences, blockquotes, and inline bold/italic/strike/code/link. Anything
 * unrecognized survives as escaped text — the note is the source of
 * truth and we never want publishing to lose content.
 */
object MarkdownHtml {

    private const val SENTINEL_CHAR = '\u0001'

    /** Schemes allowed in a link target. Anything else becomes "#". */
    private val SAFE_SCHEMES = listOf("http://", "https://", "mailto:")

    fun render(markdown: String): String {
        val out = StringBuilder()
        val lines = markdown.replace("\r\n", "\n").split("\n")
        var i = 0
        val para = mutableListOf<String>()

        fun flushParagraph() {
            if (para.isEmpty()) return
            out.append("<p>").append(inline(para.joinToString(" "))).append("</p>\n")
            para.clear()
        }

        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trimStart()
            when {
                line.startsWith("```") -> {
                    flushParagraph()
                    i++
                    val code = StringBuilder()
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        code.append(lines[i]).append('\n')
                        i++
                    }
                    i++ // closing fence (or end of input)
                    // Code is escaped, never inline-parsed: a fence is
                    // exactly where someone pastes markup.
                    out.append("<pre><code>").append(escape(code.toString())).append("</code></pre>\n")
                }

                line.startsWith("#") -> {
                    flushParagraph()
                    val level = line.takeWhile { it == '#' }.length.coerceAtMost(6)
                    val text = line.drop(level).trim()
                    out.append("<h$level>").append(inline(text)).append("</h$level>\n")
                    i++
                }

                line.startsWith("> ") || line == ">" -> {
                    flushParagraph()
                    val quote = mutableListOf<String>()
                    while (i < lines.size) {
                        val l = lines[i].trimStart()
                        if (!l.startsWith(">")) break
                        quote.add(l.removePrefix(">").removePrefix(" "))
                        i++
                    }
                    out.append("<blockquote><p>")
                        .append(inline(quote.joinToString(" ")))
                        .append("</p></blockquote>\n")
                }

                line.startsWith("- ") || line.startsWith("* ") -> {
                    flushParagraph()
                    out.append("<ul>\n")
                    while (i < lines.size) {
                        val l = lines[i].trimStart()
                        if (!(l.startsWith("- ") || l.startsWith("* "))) break
                        out.append("<li>").append(inline(l.drop(2).trim())).append("</li>\n")
                        i++
                    }
                    out.append("</ul>\n")
                }

                ORDERED.matches(line) -> {
                    flushParagraph()
                    out.append("<ol>\n")
                    while (i < lines.size) {
                        val l = lines[i].trimStart()
                        if (!ORDERED.matches(l)) break
                        out.append("<li>")
                            .append(inline(l.dropWhile { it.isDigit() }.drop(1).trim()))
                            .append("</li>\n")
                        i++
                    }
                    out.append("</ol>\n")
                }

                line.isBlank() -> {
                    flushParagraph()
                    i++
                }

                else -> {
                    para.add(line)
                    i++
                }
            }
        }
        flushParagraph()
        return out.toString().trimEnd()
    }

    // Matches MarkdownText's parser, including `1)` — otherwise a list
    // renders in the app but flattens to a paragraph once published.
    private val ORDERED = Regex("""^\d{1,9}[.)]\s+.*""")

    /** HTML-escape. Applied to every piece of user text without exception. */
    fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            // Drop the placeholder marker so content can't forge one.
            SENTINEL_CHAR -> Unit
            else -> append(c)
        }
    }

    /**
     * Inline spans. Text is escaped first, then our own tags are woven
     * in — so the markers we recognize can never be forged by content.
     */
    internal fun inline(text: String): String {
        var s = escape(text)

        // Code spans and links are lifted out before emphasis runs and
        // put back after. Without this, the emphasis pass reaches inside
        // them: `**x**` in backticks would render bold, and a URL like
        // example.com/a_b_c would sprout an <em> mid-path.
        val held = mutableListOf<String>()
        fun hold(html: String): String {
            held.add(html)
            return "$SENTINEL${held.size - 1}$SENTINEL"
        }

        s = CODE.replace(s) { hold("<code>${it.groupValues[1]}</code>") }
        s = LINK.replace(s) { m ->
            val href = safeHref(m.groupValues[2])
            // The label is ordinary text, so it still gets emphasis.
            val label = emphasis(m.groupValues[1])
            hold("<a href=\"$href\" rel=\"noopener noreferrer nofollow\">$label</a>")
        }

        s = emphasis(s)

        held.forEachIndexed { idx, html ->
            s = s.replace("$SENTINEL$idx$SENTINEL", html)
        }
        return s
    }

    private fun emphasis(s: String): String {
        var r = BOLD.replace(s) { "<strong>${it.groupValues[1]}</strong>" }
        r = STRIKE.replace(r) { "<del>${it.groupValues[1]}</del>" }
        r = ITALIC_STAR.replace(r) { "<em>${it.groupValues[1]}</em>" }
        r = ITALIC_UNDER.replace(r) { "<em>${it.groupValues[1]}</em>" }
        return r
    }

    /**
     * Placeholder marker. A control character so it can't collide with
     * anything meaningful, and [escape] strips it from input so a note
     * can't forge one and inject a held fragment.
     */
    private const val SENTINEL = "\u0001"

    /**
     * Only http/https/mailto survive. `javascript:` (and anything else
     * exotic) collapses to "#" — this page is served from the author's
     * own origin, so a live scheme here is script execution.
     */
    internal fun safeHref(raw: String): String {
        val trimmed = raw.trim()
        val lower = trimmed.lowercase()
        val ok = SAFE_SCHEMES.any { lower.startsWith(it) } ||
            // Relative links can't carry a scheme, so they're fine.
            (!lower.contains(':') && !lower.startsWith("//"))
        return if (ok) trimmed else "#"
    }

    private val LINK = Regex("""\[([^\]]*)\]\(([^)\s]+)\)""")
    private val CODE = Regex("""`([^`]+)`""")
    private val BOLD = Regex("""\*\*([^*]+)\*\*""")
    private val STRIKE = Regex("""~~([^~]+)~~""")
    private val ITALIC_STAR = Regex("""(?<![*\w])\*([^*\n]+)\*(?![*\w])""")
    private val ITALIC_UNDER = Regex("""(?<![_\w])_([^_\n]+)_(?![_\w])""")
}
