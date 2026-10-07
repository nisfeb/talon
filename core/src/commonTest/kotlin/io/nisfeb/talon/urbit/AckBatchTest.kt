package io.nisfeb.talon.urbit

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Eyre's ack prunes every event up to the id it names, so one ack covers
 * a run. One PUT per event made each fact cost the ship a second event.
 */
class AckBatchTest {
    @Test
    fun a_burst_is_acked_every_twenty_and_the_rest_once_it_goes_quiet() = runTest {
        val ids = Channel<Long>(Channel.UNLIMITED)
        val acked = mutableListOf<Long>()
        val batching = launch { ackInBatches(ids) { acked += it } }
        (1L..45L).forEach { ids.send(it) }
        runCurrent()
        assertEquals(listOf(20L, 40L), acked, "every twentieth at once")
        advanceTimeBy(ACK_QUIET_MS - 1)
        runCurrent()
        assertEquals(listOf(20L, 40L), acked, "the rest wait for quiet")
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(20L, 40L, 45L), acked)
        ids.close()
        batching.join()
        assertEquals(3, acked.size, "nothing left to ack on close")
    }

    @Test
    fun the_newest_is_acked_whatever_order_ids_came_in() = runTest {
        val ids = Channel<Long>(Channel.UNLIMITED)
        val acked = mutableListOf<Long>()
        val batching = launch { ackInBatches(ids, every = 3) { acked += it } }
        listOf(7L, 9L, 8L).forEach { ids.send(it) }
        runCurrent()
        assertEquals(listOf(9L), acked)
        ids.close()
        batching.join()
    }

    @Test
    fun what_waits_is_acked_as_the_stream_closes() = runTest {
        val ids = Channel<Long>(Channel.UNLIMITED)
        val acked = mutableListOf<Long>()
        val batching = launch { ackInBatches(ids) { acked += it } }
        ids.send(1); ids.send(2)
        runCurrent()
        assertEquals(emptyList(), acked)
        ids.close()
        batching.join()
        assertEquals(listOf(2L), acked)
    }

    @Test
    fun a_failed_ack_does_not_stop_the_batching() = runTest {
        val ids = Channel<Long>(Channel.UNLIMITED)
        val acked = mutableListOf<Long>()
        var fail = true
        val batching = launch { ackInBatches(ids, every = 1) { if (fail) { fail = false; error("dropped") } else acked += it } }
        ids.send(1); ids.send(2)
        runCurrent()
        assertEquals(listOf(2L), acked)
        ids.close()
        batching.join()
    }
}
