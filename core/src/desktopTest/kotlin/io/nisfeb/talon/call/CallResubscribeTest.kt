package io.nisfeb.talon.call

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test

/**
 * A kicked /calls subscription must resubscribe itself.
 *
 * Gall sends %kick during agent state migrations (Tlon does this) and
 * eyre turns it into {"response":"quit"} with no error text. Nothing
 * used to ask again, so the client kept poking fine — calls could be
 * placed — while no ring, accept or hangup ever arrived again until
 * the app was killed.
 */
class CallResubscribeTest {

    @Test
    fun aQuitResubscribesOnTheSameChannelAndRingsStillArrive() = runBlocking<Unit> {
        val h = TrunkHarness()
        val controller = CallController(
            h.session,
            CallEngineProvider { error("no media needed to ring") },
        )
        try {
            controller.start()
            h.awaitConnected()

            h.emit("""{"id":1,"response":"quit"}""")

            // The regression: a second subscribe, without the stream
            // dropping (the harness SSE never closes, so a reconnect
            // can't produce this — only the quit handler can).
            h.await {
                h.putsSnapshot().count { it.contains("\"action\":\"subscribe\"") } >= 2
            }

            // And the resubscribed watch still delivers.
            h.emitFact("""{"recv":{"from":"~zod","sig":{"ring":{"id":"c1"}}}}""")
            withTimeout(10_000) {
                controller.state.first { it is CallUiState.Incoming }
            }
        } finally {
            controller.stop()
        }
    }

    @Test
    fun aQuitWhoseResubscribeIsLostGivesTheChannelUpForOneThatWatches() = runBlocking<Unit> {
        val h = TrunkHarness()
        val controller = CallController(
            h.session,
            CallEngineProvider { error("no media needed to ring") },
        )
        try {
            controller.start()
            h.awaitConnected()
            val first = h.putsWithPaths().first().first
            val lose = java.util.concurrent.atomic.AtomicBoolean(true)
            h.onPut = { body ->
                if (body.contains("\"action\":\"subscribe\"") && lose.getAndSet(false)) throw java.io.IOException("lost")
            }

            h.emit("""{"id":1,"response":"quit"}""")

            // Kept, the channel would be resumed without its watch and
            // no ring would ever arrive. A new one watches /calls.
            h.await(15_000) {
                h.putsWithPaths().any { (path, body) ->
                    path != first && body.contains("\"action\":\"subscribe\"")
                }
            }
            h.endStream()
            h.await(15_000) { controller.connected.value }
            h.emitFact("""{"recv":{"from":"~zod","sig":{"ring":{"id":"c1"}}}}""")
            withTimeout(10_000) {
                controller.state.first { it is CallUiState.Incoming }
            }
        } finally {
            controller.stop()
        }
    }
}
