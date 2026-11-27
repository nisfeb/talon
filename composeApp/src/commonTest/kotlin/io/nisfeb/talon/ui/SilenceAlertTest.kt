package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The notification for a ship silent thirty minutes (sneagan, 2026-11-27):
 * once an outage, back only when the ship speaks, and never of time
 * nobody was trying in (asleep, frozen, offline, not listening).
 */
class SilenceAlertTest {
    private val min = 60_000L
    private val start = 1_000_000_000L

    /** Ticks a minute apart from [from] for [minutes], each with [heard]; the steps that were not None. */
    private fun SilenceAlert.run(from: Long, minutes: Int, heard: Long?, listening: Boolean = true, online: Boolean = true) =
        (0..minutes).map { from + it * min }.mapNotNull { t ->
            tick(t, heard, listening, online).takeIf { it != SilenceStep.None }?.let { t to it }
        }

    private fun alert() = SilenceAlert(alertAfterMs = 30 * min, maxGapMs = 3 * min)

    @Test
    fun `posts once, at thirty minutes from the last heard, and not again while it stays silent`() {
        val a = alert()
        val steps = a.run(start, 60, heard = start)
        assertEquals(listOf(start + 30 * min to SilenceStep.Post(start)), steps)
    }

    @Test
    fun `heard from within the half hour, nothing is said`() {
        val a = alert()
        // Heard every minute: the stream's heartbeats.
        val steps = (0..90).mapNotNull { i -> a.tick(start + i * min, start + i * min, listening = true).takeIf { it != SilenceStep.None } }
        assertEquals(emptyList(), steps)
    }

    @Test
    fun `a word from the ship takes it back, and the next outage is said again`() {
        val a = alert()
        assertEquals(SilenceStep.Post(start), a.run(start, 30, heard = start).single().second)
        // The heard mark moves: something came.
        val back = start + 31 * min
        assertEquals(SilenceStep.Clear, a.tick(back, back, listening = true))
        assertEquals(listOf(back + 30 * min to SilenceStep.Post(back)), a.run(back + min, 40, heard = back))
    }

    @Test
    fun `asleep or frozen is not silence, and the count starts again on waking`() {
        val a = alert()
        a.run(start, 10, heard = start)
        // Two hours with no tick: the laptop's lid shut, the phone dozing.
        val wake = start + 130 * min
        assertEquals(emptyList(), a.run(wake, 29, heard = start), "the stale heard mark is not thirty minutes of trying")
        assertEquals(SilenceStep.Post(start), a.tick(wake + 30 * min, start, listening = true), "it says since the real last heard")
    }

    @Test
    fun `offline it is the phone that is out of reach, not the ship`() {
        val a = alert()
        assertEquals(emptyList(), a.run(start, 60, heard = start, online = false))
        // Thirty minutes from the last tick without a network.
        val lastOffline = start + 60 * min
        assertEquals(emptyList(), a.run(lastOffline + min, 28, heard = start))
        assertEquals(SilenceStep.Post(start), a.tick(lastOffline + 30 * min, start, listening = true))
    }

    @Test
    fun `a repo not listening never posts, and stopping takes a posted one back`() {
        assertEquals(emptyList(), alert().run(start, 60, heard = start, listening = false), "signed out has its own notice")
        val a = alert()
        a.run(start, 30, heard = start)
        assertEquals(SilenceStep.Clear, a.tick(start + 31 * min, start, listening = false))
        assertEquals(emptyList(), a.run(start + 32 * min, 10, heard = start, listening = false))
    }

    @Test
    fun `posted, it stays up while nothing comes, whatever the screen says meanwhile`() {
        // Coming to the front mid-outage shows Connecting for a moment;
        // only bytes heard take the notice back, so no clear and no repost.
        val a = alert()
        a.run(start, 30, heard = start)
        assertEquals(emptyList(), a.run(start + 31 * min, 120, heard = start))
    }

    @Test
    fun `ticks slow but within the gap still count`() {
        // Three minutes apart, the most a tick may lag (a busy phone).
        val a = alert()
        val steps = (0..20).mapNotNull { i -> val t = start + i * 3 * min; a.tick(t, start, listening = true).takeIf { it != SilenceStep.None }?.let { t to it } }
        assertEquals(listOf(start + 30 * min to SilenceStep.Post(start)), steps)
    }

    @Test
    fun `never heard this run, it counts from when it began watching`() {
        val a = alert()
        assertEquals(listOf(start + 30 * min to SilenceStep.Post(start)), a.run(start, 45, heard = null))
    }
}
