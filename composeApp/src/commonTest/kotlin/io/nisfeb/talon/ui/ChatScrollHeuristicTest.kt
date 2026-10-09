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
 *    reader could see the newest message before it came. Scrolled up
 *    at all, they stay put. The size + newestId guards prevent
 *    pagination prepends from being misread as new heads.
 *
 *  - **Self-send** (user hit send): scroll unconditionally as soon
 *    as `rows.size` grows past the baseline captured at send time.
 *    Doesn't care about position. Clears the baseline on success.
 *
 * Regression risk this guards against:
 *   - rc10 bug: send → user's own message lands below the fold
 *     because the inbound near-bottom check failed mid-tick.
 *   - "yank user from history" bug: any inbound scroll without the
 *     near-bottom guard.
 */
class ChatScrollHeuristicTest {

    @Test
    fun `inbound follows a reader who could see the newest message`() {
        val d = decideAutoScroll(
            rowsSize = 100,
            newestId = "new",
            lastNewestId = "old",
            lastSize = 99,
            sawLastNewest = true,
            pendingSendBaselineSize = null,
            pendingSelfSendNewestId = null,
        )
        assertTrue(d.scrollToBottom)
    }

    // "when a new message comes in and the user is scrolled up from the
    // bottom and can't see it the app should NOT pop back to the bottom"
    // (sneagan, 2026-10-09). Twelve items up used to count as the bottom.
    @Test
    fun `inbound leaves a reader who has scrolled up where they are`() {
        val d = decideAutoScroll(
            rowsSize = 100,
            newestId = "new",
            lastNewestId = "old",
            lastSize = 99,
            sawLastNewest = false,
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
            sawLastNewest = true,
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
            sawLastNewest = false,  // reading history — doesn't matter
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
            sawLastNewest = true,
            pendingSendBaselineSize = 100,
            pendingSelfSendNewestId = null,  // captured pre-send
        )
        assertFalse(d.scrollToBottom, "no scroll until the upsert lands")
        assertEquals(100, d.nextBaseline, "baseline must persist for the next emission")
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
            sawLastNewest = true,
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
            sawLastNewest = true,
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
            sawLastNewest = true,
            pendingSendBaselineSize = null,
            pendingSelfSendNewestId = "optimistic-id",
        )
        assertFalse(d.scrollToBottom)
        assertEquals("optimistic-id", d.nextPendingSelfSendNewestId)
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
            sawLastNewest = true,
            pendingSendBaselineSize = null,
            pendingSelfSendNewestId = "optimistic-id",
        )
        assertFalse(d.scrollToBottom)
        assertEquals("optimistic-id", d.nextPendingSelfSendNewestId)
    }


    // On entry the first load of rows counted as an arrival near the
    // bottom, and its scroll cut short the "New" divider's placement.
    @Test
    fun `the first load does not move a reader the screen is still placing`() {
        val held = decideAutoScroll(
            rowsSize = 41, newestId = "m41", lastNewestId = "m40", lastSize = 40,
            sawLastNewest = true, pendingSendBaselineSize = null, pendingSelfSendNewestId = null,
            holdInbound = true,
        )
        assertFalse(held.scrollToBottom)
        val free = decideAutoScroll(
            rowsSize = 41, newestId = "m41", lastNewestId = "m40", lastSize = 40,
            sawLastNewest = true, pendingSendBaselineSize = null, pendingSelfSendNewestId = null,
            holdInbound = false,
        )
        assertTrue(free.scrollToBottom, "the same rows once placed still go to the bottom")
    }

    @Test
    fun `a send still goes to the bottom while the screen is placing`() {
        val d = decideAutoScroll(
            rowsSize = 41, newestId = "local_1", lastNewestId = "m40", lastSize = 40,
            sawLastNewest = true, pendingSendBaselineSize = 40, pendingSelfSendNewestId = null,
            holdInbound = true,
        )
        assertTrue(d.scrollToBottom)
    }

    @Test
    fun `a reader at the newest message is one who can see it, by position and by layout`() {
        // At the bottom: before the new row is laid out, and after (the
        // list keeps the row the reader was on, one up now).
        assertTrue(readerAtNewest("m40", firstVisibleItemIndex = 0, inserted = 1, visibleKeys = listOf("m40", "m39")))
        assertTrue(readerAtNewest("m40", firstVisibleItemIndex = 1, inserted = 1, visibleKeys = listOf("m40", "m39")))
        // A few rows up: the previous newest is off screen.
        assertFalse(readerAtNewest("m40", firstVisibleItemIndex = 6, inserted = 1, visibleKeys = listOf("m34", "m33")))
        assertFalse(readerAtNewest("m40", firstVisibleItemIndex = 1, inserted = 1, visibleKeys = listOf("m39", "m38")))
    }

    // 1.8.13: a jump to an old post placed the reader, then the chat's first
    // load counted as an arrival "at the bottom" and pulled them back down.
    @Test
    fun `a first load is never an arrival, and a jump the layout has not caught up with holds`() {
        assertFalse(readerAtNewest(null, firstVisibleItemIndex = 0, inserted = 256, visibleKeys = emptyList()))
        assertFalse(readerAtNewest(null, firstVisibleItemIndex = 254, inserted = 256, visibleKeys = listOf("m259")))
        // Scrolled to an old post, with the last frame still showing the bottom.
        assertFalse(readerAtNewest("m259", firstVisibleItemIndex = 238, inserted = 5, visibleKeys = listOf("m259", "m258")))
    }
}
