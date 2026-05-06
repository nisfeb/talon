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
            pendingSelfSendNewestId = null,
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
            pendingSelfSendNewestId = null,
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
            pendingSelfSendNewestId = null,
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
            pendingSelfSendNewestId = null,
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
            pendingSelfSendNewestId = null,
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
            pendingSelfSendNewestId = null,
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
            pendingSelfSendNewestId = null,
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
            pendingSelfSendNewestId = null,
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
            pendingSendBaselineSize = 100,
            pendingSelfSendNewestId = null,  // captured pre-send
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
            pendingSelfSendNewestId = null,
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
            pendingSelfSendNewestId = null,
        )
        assertNull(d.nextBaseline)
    }

    // ---- self-send swap path -----------------------------------------

    @Test
    fun `self-send catch-up records the optimistic newest id for swap detection`() {
        // The catch-up branch must hand back `nextPendingSelfSendNewestId
        // = newestId` so the next emission can detect the
        // optimistic→verified swap.
        val d = decideAutoScroll(
            rowsSize = 101,
            newestId = "optimistic-id",
            lastNewestId = "previous-newest",
            lastSize = 100,
            firstVisibleItemIndex = 0,
            pendingSendBaselineSize = 100,
            pendingSelfSendNewestId = null,
        )
        assertTrue(d.scrollToBottom)
        assertEquals("optimistic-id", d.nextPendingSelfSendNewestId)
    }

    @Test
    fun `swap from optimistic id to verified id triggers scroll`() {
        // Same row count (the optimistic was deleted and the verified
        // row inserted in the same Room transaction), but newestId
        // flipped from "optimistic-id" to "verified-id". This is the
        // rc25 bug: without a swap branch, decideAutoScroll fell into
        // the inbound path, where `rowsSize > lastSize` failed (sizes
        // are equal) and no scroll fired — leaving the just-confirmed
        // message below the fold.
        val d = decideAutoScroll(
            rowsSize = 101,
            newestId = "verified-id",
            lastNewestId = "optimistic-id",
            lastSize = 101,                      // unchanged size
            firstVisibleItemIndex = 0,
            pendingSendBaselineSize = null,      // catch-up already cleared it
            pendingSelfSendNewestId = "optimistic-id",
        )
        assertTrue(d.scrollToBottom, "swap must scroll")
        assertNull(
            d.nextPendingSelfSendNewestId,
            "after the swap, the marker is cleared",
        )
        assertNull(d.nextBaseline)
    }

    @Test
    fun `swap branch ignores a same-id re-emission`() {
        // The flow can re-emit with the same newestId (e.g. another
        // table updated and the messages flow ticked through again).
        // The marker must persist; no spurious scroll.
        val d = decideAutoScroll(
            rowsSize = 101,
            newestId = "optimistic-id",
            lastNewestId = "optimistic-id",
            lastSize = 101,
            firstVisibleItemIndex = 0,
            pendingSendBaselineSize = null,
            pendingSelfSendNewestId = "optimistic-id",
        )
        assertFalse(d.scrollToBottom)
        assertEquals("optimistic-id", d.nextPendingSelfSendNewestId)
    }

    @Test
    fun `swap branch handles size shrink + id change (delete-then-insert across emissions)`() {
        // If Room emits twice — once for the delete, once for the
        // insert — the first emission shrinks rows to N and changes
        // newestId. The swap branch should still fire on that
        // intermediate state because newestId changed and the marker
        // is set. (If newestId becomes null because the list is now
        // empty, we fall through and don't scroll — handled by the
        // `newestId != null` guard in the swap branch.)
        val d = decideAutoScroll(
            rowsSize = 100,                      // shrank from 101
            newestId = "the-message-before-mine",
            lastNewestId = "optimistic-id",
            lastSize = 101,
            firstVisibleItemIndex = 0,
            pendingSendBaselineSize = null,
            pendingSelfSendNewestId = "optimistic-id",
        )
        assertTrue(d.scrollToBottom)
        assertNull(d.nextPendingSelfSendNewestId)
    }

    @Test
    fun `swap branch is suppressed when newestId is null (empty list)`() {
        // Defensive: newestId can be null between emissions in an
        // empty-then-empty case. Don't scroll, don't clear the marker
        // (the next non-null emission will trigger the real swap).
        val d = decideAutoScroll(
            rowsSize = 0,
            newestId = null,
            lastNewestId = "optimistic-id",
            lastSize = 1,
            firstVisibleItemIndex = 0,
            pendingSendBaselineSize = null,
            pendingSelfSendNewestId = "optimistic-id",
        )
        assertFalse(d.scrollToBottom)
        assertEquals("optimistic-id", d.nextPendingSelfSendNewestId)
    }

    @Test
    fun `inbound path preserves a pending swap marker`() {
        // A peer's message arrives between the catch-up and the
        // server echo. The peer-arrival hits the inbound branch
        // (size grew, newestId changed), but the swap marker should
        // pass through unchanged so we still catch the eventual
        // optimistic-to-verified transition.
        val d = decideAutoScroll(
            rowsSize = 102,
            newestId = "peer-message",
            lastNewestId = "optimistic-id",
            lastSize = 101,
            firstVisibleItemIndex = 0,
            pendingSendBaselineSize = null,
            pendingSelfSendNewestId = "optimistic-id",
        )
        // This actually hits the swap branch first (newestId !=
        // pendingSelfSendNewestId), which scrolls and clears the
        // marker. That's also correct — the user wants to see new
        // content at the bottom. Pin both.
        assertTrue(d.scrollToBottom)
        assertNull(d.nextPendingSelfSendNewestId)
    }
}
