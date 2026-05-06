package io.nisfeb.talon.ui

/**
 * Pure decision function for the chat-list auto-scroll heuristic
 * extracted from `DmChatScreen` so it has tests instead of an
 * "I think this works" UI smoke. Three scroll paths:
 *
 *  - **Inbound**: a peer sent us a message; we scroll to the bottom
 *    only if the user was already near it (`firstVisibleItemIndex
 *    <= 12`). Reading older history isn't yanked away.
 *  - **Self-send catch-up**: the user hit send; their optimistic
 *    upsert lands and `rows.size` grows past the baseline. We scroll
 *    unconditionally — their message belongs in view regardless of
 *    where they were scrolled.
 *  - **Self-send swap**: the server echoes the message a beat later.
 *    The optimistic row gets reaped and a verified row is inserted
 *    in its place. Net `rows.size` is the same as right after the
 *    catch-up scroll, but `newestId` flips from the optimistic id to
 *    the verified id. LazyColumn doesn't preserve the user's logical
 *    position across that swap, so without a second scroll the
 *    just-verified message ends up below the fold.
 */
data class ScrollDecision(
    val scrollToBottom: Boolean,
    val nextBaseline: Int?,
    /** When the self-send catch-up fired, this is the id of the
     *  optimistic row we're now waiting to swap. The decision
     *  function clears it as soon as the swap is observed. Null
     *  outside that window. */
    val nextPendingSelfSendNewestId: String?,
)

/**
 * @param rowsSize current `rows.size` after the latest emission.
 * @param newestId id of the newest message in `rows`, or null when
 *   the list is empty. Pulled via the existing `newestMessageId`
 *   walker.
 * @param lastNewestId same id from the previous emission. Used to
 *   detect "a new head landed" vs "pagination prepended older rows".
 * @param lastSize `rows.size` from the previous emission.
 * @param firstVisibleItemIndex caller's `listState.firstVisibleItemIndex`.
 *   Under reverseLayout, 0 means "newest is fully visible".
 * @param pendingSendBaselineSize size captured at the moment doSend
 *   fired forceBottomTick. Null when no self-send is pending.
 * @param pendingSelfSendNewestId id of the optimistic row from the
 *   most recent catch-up scroll, or null. Set by the catch-up
 *   branch; the decision function clears it on the swap.
 */
fun decideAutoScroll(
    rowsSize: Int,
    newestId: String?,
    lastNewestId: String?,
    lastSize: Int,
    firstVisibleItemIndex: Int,
    pendingSendBaselineSize: Int?,
    pendingSelfSendNewestId: String?,
): ScrollDecision {
    // Self-send catch-up: when our optimistic upsert has landed (rows
    // grew past the baseline), scroll regardless of position — the
    // user explicitly hit send. Snapshot the newest id so the swap
    // branch below can detect when the server-echoed verified row
    // takes its place.
    if (pendingSendBaselineSize != null && rowsSize > pendingSendBaselineSize) {
        return ScrollDecision(
            scrollToBottom = true,
            nextBaseline = null,
            nextPendingSelfSendNewestId = newestId,
        )
    }
    // Self-send swap: the verified row replaced the optimistic. Same
    // visible content, but LazyColumn's reverseLayout doesn't pin the
    // logical "newest" across an id change, so the just-confirmed row
    // ends up below the fold. Snap to bottom and clear the marker.
    if (pendingSelfSendNewestId != null &&
        newestId != null &&
        newestId != pendingSelfSendNewestId
    ) {
        return ScrollDecision(
            scrollToBottom = true,
            nextBaseline = null,
            nextPendingSelfSendNewestId = null,
        )
    }
    // Inbound: only when a NEW newest landed AND user is near the
    // bottom. The size guard catches pagination prepends (newer
    // existing message at the same position with more rows behind it).
    val gotNewerHead = newestId != null &&
        newestId != lastNewestId &&
        rowsSize > lastSize
    val shouldScroll = gotNewerHead && firstVisibleItemIndex <= NEAR_BOTTOM_THRESHOLD
    return ScrollDecision(
        scrollToBottom = shouldScroll,
        nextBaseline = pendingSendBaselineSize,
        nextPendingSelfSendNewestId = pendingSelfSendNewestId,
    )
}

/** Items-from-bottom threshold for the inbound auto-scroll. Past
 *  this, the user is reading history and shouldn't be yanked. */
private const val NEAR_BOTTOM_THRESHOLD = 12
