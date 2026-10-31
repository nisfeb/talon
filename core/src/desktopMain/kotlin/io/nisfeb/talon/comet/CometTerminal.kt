package io.nisfeb.talon.comet

/**
 * What we read off vere's terminal, and how. Vere refuses to run
 * without a tty ("use -t to disable interactivity"), and with -t there
 * is no dojo and no way to learn the login code — so the runtime gets a
 * pseudo-terminal and we scrape exactly two things from it: the prompt
 * and the answer to `+code`. Everything here is pure and tested; the
 * pty plumbing lives in DesktopLocalShip.
 */
object CometTerminal {
    private val ANSI = Regex("\\[[0-9;?]*[ -/]*[@-~]")
    // A comet's prompt is abbreviated: `~falwed_litzod:dojo>` for
    // ~falwed-bosluc-navheb-sapbyl--silwep-sonfep-tilsen-litzod. So the
    // prompt only tells us the dojo is up; the full name comes from the
    // login cookie (DesktopLocalShip) or the boot line (minedShip).
    private val PROMPT = Regex("(~[a-z_-]+):dojo>")
    // Four words not glued to more of the same: a comet's name holds
    // runs like `tabdec-hopsep-rilmer-riglup` that look exactly like a
    // code, and the terminal prints the name often. Inside a name the
    // run is preceded by `~` or `-` or followed by `-`; a code is not.
    private val CODE = Regex("(?<![~a-z-])([a-z]{6}-[a-z]{6}-[a-z]{6}-[a-z]{6})(?![a-z-])")
    // Vere's busy spinner: `«behn»` and friends redrawn with backspaces
    // on the prompt line, often right around a dojo answer.
    private val SPINNER = Regex("[|/\\\\-]?«[a-z-]+»")
    private val FOUND = Regex("boot: found comet (~[a-z-]+)")

    /** Terminal output with control sequences, the spinner, backspaces
     *  and carriage returns gone. */
    fun clean(raw: String): String =
        SPINNER.replace(ANSI.replace(raw, ""), "").replace("\r", "").replace("\u0008", "")

    /** The (possibly abbreviated) ship whose dojo prompt is showing. */
    fun promptShip(text: String): String? = PROMPT.findAll(clean(text)).lastOrNull()?.groupValues?.get(1)

    /** The comet name vere announces while mining, before any prompt. */
    fun minedShip(text: String): String? = FOUND.find(clean(text))?.groupValues?.get(1)

    /** The `+code` answer in [text]: the first code-shaped token that is
     *  not part of a ship name. Only feed this text that arrived after
     *  the command was sent, so the answer is the first thing found. */
    fun code(text: String): String? = CODE.find(clean(text))?.groupValues?.get(1)

    /** The most recent line worth showing on a progress screen. */
    fun lastDetail(text: String): String =
        clean(text).lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty()

    /**
     * The public HTTP port from a pier's `.http.ports` file, whose lines
     * look like `8099 insecure public` and `12321 insecure loopback`.
     */
    fun publicPort(httpPorts: String): Int? =
        httpPorts.lineSequence()
            .map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size >= 3 && it[2] == "public" }
            ?.get(0)?.toIntOrNull()
}

/**
 * Vere's terminal drawn the way a terminal would draw it, for the dojo
 * panel. Vere redraws its prompt line in place: back to the start of the
 * line, erase it, draw it again; its busy spinner is backspaces over the
 * last frame. Text with those controls stripped piles every redraw up
 * side by side (a user saw his prompt and command twice and took the
 * dojo for broken), and a long busy spell filled a window of raw text
 * with nothing but spinner frames. Fed as output arrives, so each redraw
 * lands where vere put it and the lines kept are lines.
 *
 * Vere draws every line on the bottom row (ESC[40;1H, erase, the line,
 * a newline that scrolls it up) and then the prompt there again, so one
 * line deep is all it needs.
 * ponytail: the row of a move is ignored, and so are moves up and down;
 * a full-screen program in the dojo would need a grid.
 */
internal class TerminalScreen(private val maxLines: Int = 400) {
    private val lines = ArrayDeque<StringBuilder>().apply { add(StringBuilder()) }
    private var col = 0
    private var savedCol = 0
    // An escape sequence can end in the next chunk.
    private var esc: StringBuilder? = null

    fun feed(text: String) {
        for (c in text) step(c)
    }

    private fun step(c: Char) {
        val e = esc
        if (e != null) {
            e.append(c)
            val first = e[0]
            when {
                // Only CSI (ESC [) is drawn on. A character-set choice
                // (ESC ( B, sent with a colour reset) takes one character
                // more; any other escape is two characters.
                first in "()*+" -> if (e.length == 2) esc = null
                first != '[' -> esc = null
                e.length > 1 && c in '@'..'~' -> { csi(e.substring(1)); esc = null }
                e.length > 32 -> esc = null
            }
            return
        }
        when (c) {
            '\u001b' -> esc = StringBuilder()
            '\r' -> col = 0
            '\n' -> {
                lines.addLast(StringBuilder())
                col = 0
                while (lines.size > maxLines) lines.removeFirst()
            }
            '\b' -> if (col > 0) col--
            else -> if (c >= ' ') put(c)
        }
    }

    private fun put(c: Char) {
        val line = lines.last()
        while (line.length < col) line.append(' ')
        if (col < line.length) line.setCharAt(col, c) else line.append(c)
        col++
    }

    private fun csi(body: String) {
        val params = body.dropLast(1).trimStart('?').split(';').map { it.toIntOrNull() }
        val n = params.first()
        val line = lines.last()
        when (body.last()) {
            'K' -> when (n ?: 0) {
                0 -> if (col < line.length) line.setLength(col)
                1 -> for (i in 0 until minOf(col + 1, line.length)) line.setCharAt(i, ' ')
                else -> line.setLength(0)
            }
            'G' -> col = ((n ?: 1) - 1).coerceAtLeast(0)
            // To a row and column: the row is the bottom one, the prompt's.
            'H', 'f' -> col = ((params.getOrNull(1) ?: 1) - 1).coerceAtLeast(0)
            's' -> savedCol = col
            'u' -> col = savedCol
            'C' -> col += n ?: 1
            'D' -> col = (col - (n ?: 1)).coerceAtLeast(0)
            // Colour, cursor shown or hidden, and the rest draw nothing here.
        }
    }

    /** Whether vere's spinner is on the line it is drawing: an event still running. */
    fun busy(): Boolean = SPINNER.containsMatchIn(lines.last())

    /** The lines kept, spinner left out: the busy flag says that instead, and a frame a tenth of a second is not news. */
    fun text(): String = lines.joinToString("\n") { SPINNER.replace(it, "").trimEnd() }

    private companion object {
        val SPINNER = Regex("[|/\\\\-]?«[a-z-]+»")
    }
}
