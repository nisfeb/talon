package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the chat list says of the ship: connected while its stream has
 * anything in it (eyre's heartbeats included), unreachable only once it
 * has had nothing for a while, so a reconnect's seconds never show.
 */
class SyncStatusTest {
    private val t = SyncThresholds(heardRecentlyMs = 75_000, connectGraceMs = 20_000)
    private val now = 1_000_000_000L

    private fun status(heardAgo: Long?, triedAgo: Long = 600_000, signedOut: Boolean = false, syncing: Boolean = false, pending: Int = 0) =
        syncStatus(signedOut, heardAgo?.let { now - it }, now - triedAgo, now, syncing, pending, t)

    @Test
    fun `heard within three heartbeats is connected, and longer is out of reach since then`() {
        assertEquals(SyncStatus.Connected(10_000, syncing = false, pending = 0), status(10_000))
        assertEquals(SyncStatus.Connected(75_000, syncing = false, pending = 0), status(75_000), "at the mark, still")
        assertEquals(SyncStatus.Unreachable(now - 75_001, pending = 0), status(75_001))
    }

    @Test
    fun `never heard is connecting for a while, then out of reach since it began`() {
        assertEquals(SyncStatus.Connecting, status(null, triedAgo = 5_000))
        assertEquals(SyncStatus.Connecting, status(null, triedAgo = 20_000))
        assertEquals(SyncStatus.Unreachable(now - 20_001, pending = 0), status(null, triedAgo = 20_001))
    }

    @Test
    fun `a lapsed login outranks everything`() {
        assertEquals(SyncStatus.SignedOut, status(1_000, signedOut = true))
        assertEquals(SyncStatus.SignedOut, status(null, signedOut = true))
    }

    @Test
    fun `what waits is carried either way`() {
        assertEquals(SyncStatus.Connected(1_000, syncing = true, pending = 3), status(1_000, syncing = true, pending = 3))
        assertEquals(SyncStatus.Unreachable(now - 200_000, pending = 2), status(200_000, pending = 2))
    }

    @Test
    fun `each says what it knows and what helps`() {
        val at = { _: Long -> "2:02 PM" }
        assertEquals("Connected to ~zod", syncHeadline(SyncStatus.Connected(12_000, false, 0), "~zod", now, at))
        assertEquals("Heard from it 12 s ago.", syncDetail(SyncStatus.Connected(12_000, false, 0)))
        assertEquals("Syncing with ~zod…", syncHeadline(SyncStatus.Connected(1_000, true, 0), "~zod", now, at))
        assertEquals("Sending 2 to ~zod…", syncHeadline(SyncStatus.Connected(1_000, false, 2), "~zod", now, at))
        assertEquals("Connecting to ~zod…", syncHeadline(SyncStatus.Connecting, "~zod", now, at))
        assertEquals("Can't reach ~zod since 2:02 PM", syncHeadline(SyncStatus.Unreachable(0, 0), "~zod", now, at))
        assertEquals("Can't reach ~zod since t123", syncHeadline(SyncStatus.Unreachable(123, 0), "~zod", now) { "t$it" }, "since the time it was last heard")
        assertEquals("Talon keeps trying.", syncDetail(SyncStatus.Unreachable(0, 0)))
        assertEquals("1 waiting to send. Talon keeps trying.", syncDetail(SyncStatus.Unreachable(0, 1)))
        assertEquals("3 waiting to send. Talon keeps trying.", syncDetail(SyncStatus.Unreachable(0, 3)))
        assertEquals("Signed out of ~zod", syncHeadline(SyncStatus.SignedOut, "~zod", now, at))
        assertEquals("New messages wait until you sign in again.", syncDetail(SyncStatus.SignedOut))
    }

    @Test
    fun `how long ago reads as a person says it`() {
        assertEquals("just now", agoLabel(4_999))
        assertEquals("5 s ago", agoLabel(5_000))
        assertEquals("59 s ago", agoLabel(59_999))
        assertEquals("1 min ago", agoLabel(60_000))
        assertEquals("3 min ago", agoLabel(200_000))
    }

    @Test
    fun `since is a time today and a date before it`() {
        val day = 86_400_000L
        assertEquals(io.nisfeb.talon.util.formatClock(now - 60_000), sinceLabel(now - 60_000, now))
        assertEquals(io.nisfeb.talon.util.formatMonthDayClock(now - 3 * day), sinceLabel(now - 3 * day, now))
    }
}
