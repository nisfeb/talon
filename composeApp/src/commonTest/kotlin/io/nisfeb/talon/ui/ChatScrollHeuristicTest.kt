package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pin the chat-list auto-scroll decision. Two paths matter:
 *
 *  - **Inbound** (peer's message arrived): scroll only when the
 *    user is near the bottom (firstVisibleItemIndex <= 12). The
 *    size + newestId guards prevent pagination prepends from being
 *    misread as new heads.
 *
 *  - **Self-send** (user hit send): scroll unconditionally as soon
 *    as `rows.size` grows past the baseline captured at send time.
 *    Doesn't care about position. Clears the baseline on success.
 *
 * Regression risk this guards against:
 *   - rc10 bug: send → user's own message lands below the fold
 *     because the inbound `firstVisibleItemIndex <= 12` check failed
 *     mid-tick.
 *   - "yank user from history" bug: any inbound scroll without the
 *     near-bottom guard.
 */
class ChatScrollHeuristicTest {

    @Test
    fun `inbound near bottom triggers scroll`() {
        val d = decideAutoScroll(
            rowsSize = 100,
            newestId = "new",
            lastNewestId = "old",
            lastSize = 99,
            firstVisibleItemIndex = 0,
            pendingSendBaselineSize = null,
        )
        assertTrue(d.scrollToBottom)
        assertNull(d.nextBaseline)
    }

    @Test
    fun `inbound away from bottom does NOT scroll`() {
        // User scrolled up to read history — peer message arrives but
        // we leave them where they are.
        val d = decideAutoScroll(
            rowsSize = 100,
            newestId = "new",
            lastNewestId = "old",
            lastSize = 99,
            firstVisibleItemIndex = 50,
            pendingSendBaselineSize = null,
        )
        assertFalse(d.scrollToBottom)
    }

    @Test
    fun `inbound right at the threshold edge still scrolls`() {
        val d = decideAutoScroll(
            rowsSize = 100,
            newestId = "new",
            lastNewestId = "old",
            lastSize = 99,
            firstVisibleItemIndex = 12,  // exactly at threshold
            pendingSendBaselineSize = null,
        )
        assertTrue(d.scrollToBottom)
    }

    @Test
    fun `inbound one past threshold does NOT scroll`() {
        val d = decideAutoScroll(
            rowsSize = 100,
            newestId = "new",
            lastNewestId = "old",
            lastSize = 99,
            firstVisibleItemIndex = 13,
            pendingSendBaselineSize = null,
        )
        assertFalse(d.scrollToBottom)
    }

    @Test
    fun `pagination prepend (size grew, newest unchanged) does NOT scroll`() {
        // Older messages arrived from a load-older fetch. newestId is
        // still the same as before; only the count grew. Scroll would
        // yank the user from the older history they were trying to read.
        val d = decideAutoScroll(
            rowsSize = 150,
            newestId = "stable-newest",
            lastNewestId = "stable-newest",
            lastSize = 100,
            firstVisibleItemIndex = 0,
            pendingSendBaselineSize = null,
        )
        assertFalse(d.scrollToBottom)
    }

    @Test
    fun `same-tick re-emit (no growth) does NOT scroll`() {
        // Flow re-emit with no actual change (defensive).
        val d = decideAutoScroll(
            rowsSize = 100,
            newestId = "new",
            lastNewestId = "new",
            lastSize = 100,
            firstVisibleItemIndex = 0,
            pendingSendBaselineSize = null,
        )
        assertFalse(d.scrollToBottom)
    }

    @Test
    fun `null newestId (empty list) does NOT scroll`() {
        val d = decideAutoScroll(
            rowsSize = 0,
            newestId = null,
            lastNewestId = null,
            lastSize = 0,
            firstVisibleItemIndex = 0,
            pendingSendBaselineSize = null,
        )
        assertFalse(d.scrollToBottom)
    }

    // ---- self-send path ----------------------------------------------

    @Test
    fun `self-send rows grew past baseline triggers unconditional scroll`() {
        // User was scrolled WAY up reading history. They hit send.
        // The optimistic upsert has landed (rows.size > baseline).
        // Their message lands below the fold without our intervention.
        // The decision: scroll regardless of position.
        val d = decideAutoScroll(
            rowsSize = 101,
            newestId = "their-message",
            lastNewestId = "their-message",  // even with no apparent newer head
            lastSize = 101,
            firstVisibleItemIndex = 50,  // reading history — doesn't matter
            pendingSendBaselineSize = 100,
        )
        assertTrue(d.scrollToBottom)
        assertNull(d.nextBaseline, "baseline must clear after the catch-up scroll")
    }

    @Test
    fun `self-send rows hasn't grown past baseline yet preserves baseline`() {
        // forceBottomTick fires; LaunchedEffect runs but the
        // optimistic upsert hasn't landed yet (rows.size == baseline).
        // We don't scroll, but we keep the baseline so the next
        // emission (when rows grows) catches up.
        val d = decideAutoScroll(
            rowsSize = 100,
            newestId = "head",
            lastNewestId = "head",
            lastSize = 100,
            firstVisibleItemIndex = 0,
            pendingSendBaselineSize = 100,  // captured pre-send
        )
        assertFalse(d.scrollToBottom, "no scroll until the upsert lands")
        assertEquals(100, d.nextBaseline, "baseline must persist for the next emission")
    }

    @Test
    fun `self-send catch-up wins over near-bottom guard`() {
        // Inbound logic would skip scroll (firstVisibleItemIndex > 12),
        // but self-send catch-up overrides that. The user should
        // always see their own message.
        val d = decideAutoScroll(
            rowsSize = 101,
            newestId = "self-message",
            lastNewestId = "old",
            lastSize = 100,
            firstVisibleItemIndex = 200,  // way scrolled up
            pendingSendBaselineSize = 100,
        )
        assertTrue(d.scrollToBottom)
    }

    @Test
    fun `no baseline preserves null nextBaseline`() {
        val d = decideAutoScroll(
            rowsSize = 100,
            newestId = "new",
            lastNewestId = "old",
            lastSize = 99,
            firstVisibleItemIndex = 0,
            pendingSendBaselineSize = null,
        )
        assertNull(d.nextBaseline)
    }
}
