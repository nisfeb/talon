package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class RelativeTimeTest {

    private val now = 1_700_000_000_000L  // arbitrary fixed reference

    @Test
    fun `under a minute renders as just now`() {
        assertEquals("just now", shortRelativeTime(thenMs = now - 0L, nowMs = now))
        assertEquals("just now", shortRelativeTime(thenMs = now - 1_000L, nowMs = now))
        assertEquals("just now", shortRelativeTime(thenMs = now - 59_999L, nowMs = now))
    }

    @Test
    fun `future-dated input clamps to just now`() {
        // Clock skew or forward-dated edits shouldn't render "in 3h".
        assertEquals("just now", shortRelativeTime(thenMs = now + 60_000L, nowMs = now))
    }

    @Test
    fun `minute bucket renders as Nm ago`() {
        assertEquals("1m ago", shortRelativeTime(thenMs = now - 60_000L, nowMs = now))
        assertEquals("5m ago", shortRelativeTime(thenMs = now - 5L * 60_000L, nowMs = now))
        assertEquals("59m ago", shortRelativeTime(thenMs = now - 59L * 60_000L, nowMs = now))
    }

    @Test
    fun `hour bucket renders as Nh ago`() {
        assertEquals("1h ago", shortRelativeTime(thenMs = now - 60L * 60_000L, nowMs = now))
        assertEquals("3h ago", shortRelativeTime(thenMs = now - 3L * 60 * 60_000L, nowMs = now))
        assertEquals("23h ago", shortRelativeTime(thenMs = now - 23L * 60 * 60_000L, nowMs = now))
    }

    @Test
    fun `day bucket renders as Nd ago up to one week`() {
        assertEquals("1d ago", shortRelativeTime(thenMs = now - 24L * 60 * 60_000L, nowMs = now))
        assertEquals("6d ago", shortRelativeTime(thenMs = now - 6L * 24 * 60 * 60_000L, nowMs = now))
    }

    @Test
    fun `week bucket renders as Nw ago`() {
        assertEquals("1w ago", shortRelativeTime(thenMs = now - 7L * 24 * 60 * 60_000L, nowMs = now))
        assertEquals("4w ago", shortRelativeTime(thenMs = now - 28L * 24 * 60 * 60_000L, nowMs = now))
    }

    @Test
    fun `weeks cap at 52w ago`() {
        // Anything older than a year still shows the "Nw ago" form
        // but caps so we don't render "521w ago" for ancient messages.
        assertEquals(
            "52w ago",
            shortRelativeTime(thenMs = now - 5L * 365 * 24 * 60 * 60_000L, nowMs = now),
        )
    }
}
