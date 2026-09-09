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
    private val CODE = Regex("\\b[a-z]{6}-[a-z]{6}-[a-z]{6}-[a-z]{6}\\b")
    private val FOUND = Regex("boot: found comet (~[a-z-]+)")

    /** Terminal output with control sequences and carriage returns gone. */
    fun clean(raw: String): String = ANSI.replace(raw, "").replace("\r", "")

    /** The (possibly abbreviated) ship whose dojo prompt is showing. */
    fun promptShip(text: String): String? = PROMPT.findAll(clean(text)).lastOrNull()?.groupValues?.get(1)

    /** The comet name vere announces while mining, before any prompt. */
    fun minedShip(text: String): String? = FOUND.find(clean(text))?.groupValues?.get(1)

    /** The last `+code` answer in [text]. Only trust text that arrived
     *  after the command was sent. */
    fun code(text: String): String? = CODE.findAll(clean(text)).lastOrNull()?.value

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
