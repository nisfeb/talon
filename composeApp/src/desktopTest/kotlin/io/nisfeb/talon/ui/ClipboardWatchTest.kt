package io.nisfeb.talon.ui

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The composer asks whether the clipboard holds an image every 600 ms,
 * on the UI thread. A clipboard owner that never replied froze Talon
 * (a user on Hyprland's XWayland, 2026-10-23): the ask must never wait
 * on the owner, and a hung ask must not pile up more.
 */
class ClipboardWatchTest {

    @Test
    fun `a clipboard that never answers holds no caller and starts no second ask`() {
        val asks = AtomicInteger()
        val reply = CountDownLatch(1)
        val watch = ClipboardWatch({ asks.incrementAndGet(); reply.await(30, TimeUnit.SECONDS); true })
        val started = System.nanoTime()
        repeat(50) { assertFalse(watch.hasImage(), "the last answer, until there is a new one") }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(tookMs < 1_000, "50 polls took $tookMs ms")
        Thread.sleep(100)
        assertEquals(1, asks.get(), "one ask in flight, however often it is polled")
        reply.countDown()
        val deadline = System.currentTimeMillis() + 5_000
        while (!watch.hasImage() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(watch.hasImage(), "the answer arrives once the owner replies")
        Thread.sleep(200)
        assertTrue(asks.get() < 10, "no backlog of polls ran after the reply: ${asks.get()} asks")
    }

    @Test
    fun `each poll asks again, so a copied image shows and a cleared one goes`() {
        var holds = true
        val watch = ClipboardWatch({ holds })
        fun settle(want: Boolean) {
            val deadline = System.currentTimeMillis() + 5_000
            while (watch.hasImage() != want && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertEquals(want, watch.hasImage())
        }
        settle(true)
        holds = false
        settle(false)
    }
}
