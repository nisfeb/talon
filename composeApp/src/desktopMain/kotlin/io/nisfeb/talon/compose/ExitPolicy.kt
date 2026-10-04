package io.nisfeb.talon.compose

/**
 * How Talon leaves, whatever state it is in. A process left behind
 * holds the single-instance lock ([SingleInstance]), and every launch
 * after it says Talon is already running: a user could not start Talon
 * again after pasting an image ran it out of memory.
 */
internal object ExitPolicy {
    /** How long shutting down may take before the process is halted anyway. */
    const val SHUTDOWN_GRACE_MS = 10_000L

    /**
     * Run [shutdown], then exit with [code]. If either hangs (a socket
     * that will not close, a ship that will not stop, a JVM out of
     * memory), halt after [graceMs]: halting skips what is left, and the
     * OS releases the lock.
     */
    fun exitAfterShutdown(
        code: Int = 0,
        graceMs: Long = SHUTDOWN_GRACE_MS,
        exit: (Int) -> Unit = { kotlin.system.exitProcess(it) },
        halt: (Int) -> Unit = { Runtime.getRuntime().halt(it) },
        shutdown: () -> Unit,
    ) {
        daemon("Talon-exit-deadline") {
            Thread.sleep(graceMs)
            halt(code)
        }
        daemon("Talon-shutdown") {
            runCatching(shutdown)
            exit(code)
        }
    }

    /**
     * For what nothing caught. A VM error (out of memory, a stack
     * overflow) leaves nothing in the process to trust, a window closed
     * on it among them, so it halts at once after a line in the log, and
     * the next launch starts clean. Anything else goes to [previous], as
     * before, or to the log.
     */
    fun fatalHandler(
        previous: Thread.UncaughtExceptionHandler?,
        log: (String, Throwable) -> Unit,
        halt: (Int) -> Unit = { Runtime.getRuntime().halt(it) },
    ): Thread.UncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, e ->
        if (e is VirtualMachineError) {
            runCatching { log("fatal on ${thread.name}; halting so the next launch can start", e) }
            halt(1)
        } else if (previous != null) {
            previous.uncaughtException(thread, e)
        } else {
            runCatching { log("uncaught on ${thread.name}", e) }
        }
    }

    private fun daemon(name: String, body: () -> Unit) {
        Thread { runCatching(body) }.apply {
            isDaemon = true
            this.name = name
        }.start()
    }
}
