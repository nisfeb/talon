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
}
