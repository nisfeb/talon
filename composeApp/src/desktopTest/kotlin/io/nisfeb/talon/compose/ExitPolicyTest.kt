package io.nisfeb.talon.compose

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Talon always leaves: a process left behind holds the single-instance
 * lock, and every launch after it said Talon was already running.
 */
class ExitPolicyTest {
    private val did = CopyOnWriteArrayList<String>()

    @Test
    fun `a shutdown that finishes exits with its code`() {
        val exited = CountDownLatch(1)
        ExitPolicy.exitAfterShutdown(
            code = 0, graceMs = 5_000,
            exit = { did += "exit $it"; exited.countDown() },
            halt = { did += "halt $it" },
        ) { did += "shutdown" }
        assertTrue(exited.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("shutdown", "exit 0"), did.toList())
    }

    @Test
    fun `a shutdown that hangs is halted after the grace`() {
        val halted = CountDownLatch(1)
        val hang = CountDownLatch(1)
        val started = System.nanoTime()
        ExitPolicy.exitAfterShutdown(
            code = 0, graceMs = 300,
            exit = { did += "exit $it" },
            halt = { did += "halt $it"; halted.countDown() },
        ) { hang.await() }
        assertTrue(halted.await(3, TimeUnit.SECONDS), "halted")
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 300, "not before the grace")
        assertEquals(listOf("halt 0"), did.toList(), "the stuck shutdown never got to exit")
        hang.countDown()
    }

    @Test
    fun `out of memory anywhere halts at once, with a line in the log`() {
        val logged = CopyOnWriteArrayList<String>()
        val handler = ExitPolicy.fatalHandler(previous = null, log = { m, _ -> logged += m }, halt = { did += "halt $it" })
        handler.uncaughtException(Thread.currentThread(), OutOfMemoryError("Java heap space"))
        assertEquals(listOf("halt 1"), did.toList())
        assertTrue(logged.single().startsWith("fatal on "), "$logged")
        handler.uncaughtException(Thread.currentThread(), StackOverflowError())
        assertEquals(listOf("halt 1", "halt 1"), did.toList())
    }

    @Test
    fun `anything else goes where it went before, and does not end the app`() {
        val before = CopyOnWriteArrayList<Throwable>()
        val previous = Thread.UncaughtExceptionHandler { _, e -> before += e }
        val handler = ExitPolicy.fatalHandler(previous, log = { _, _ -> did += "logged" }, halt = { did += "halt $it" })
        val e = IllegalStateException("a bug")
        handler.uncaughtException(Thread.currentThread(), e)
        assertEquals(listOf<Throwable>(e), before.toList())
        assertTrue(did.isEmpty(), "$did")
        ExitPolicy.fatalHandler(null, log = { _, _ -> did += "logged" }, halt = { did += "halt $it" })
            .uncaughtException(Thread.currentThread(), e)
        assertEquals(listOf("logged"), did.toList())
    }
}
