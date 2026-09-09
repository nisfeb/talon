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
