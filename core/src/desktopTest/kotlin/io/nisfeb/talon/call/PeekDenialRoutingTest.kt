package io.nisfeb.talon.call

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A peek's denial routes into [CallController.peekFailed] — never into
 * [CallController.onDenied], which paints the sticky join-refusal bar.
 * And "no such room" answering a peek is not a failure at all: every
 * group chat peeks on open, so the host of a group that simply has no
 * line answers exactly that, and bannering it painted red text over
 * healthy groups the moment trunk v15 made denials deliverable.
 */
class PeekDenialRoutingTest {

    private fun controller(h: TrunkHarness) = CallController(
        h.session,
        CallEngineProvider { error("no media needed to peek") },
    )

    @Test
    fun noSuchRoomIsSilence() = runBlocking<Unit> {
        val h = TrunkHarness()
        val c = controller(h)
        var denied: String? = null
        c.onDenied = { name, why -> denied = "$name: $why" }
        try {
            c.start()
            h.awaitConnected()
            c.peekRoom("~zod", "ghostline")
            h.awaitPut { it.contains("peek-room") }
            h.emitFact(
                """{"denied":{"from":"~zod","name":"ghostline","why":"no such room"}}""",
            )
            // Deliberately real-time: the fact must have been consumed
            // (silently) before we assert nothing surfaced.
            delay(500)
            assertTrue(
                c.peekFailed.value.isEmpty(),
                "a line-less group is not a failure: ${c.peekFailed.value}",
            )
            assertNull(denied, "a peek denial must never reach the join-refusal bar")
        } finally {
            c.stop()
        }
    }

    @Test
    fun notAMemberBannersInThePeekLaneOnly() = runBlocking<Unit> {
        val h = TrunkHarness()
        val c = controller(h)
        var denied: String? = null
        c.onDenied = { name, why -> denied = "$name: $why" }
        try {
            c.start()
            h.awaitConnected()
            c.peekRoom("~zod", "clubline")
            h.awaitPut { it.contains("peek-room") }
            h.emitFact(
                """{"denied":{"from":"~zod","name":"clubline","why":"not a member"}}""",
            )
            h.await { c.peekFailed.value.isNotEmpty() }
            assertEquals("not a member", c.peekFailed.value["~zod/clubline"])
            assertNull(denied, "a peek denial must never reach the join-refusal bar")
        } finally {
            c.stop()
        }
    }
}
