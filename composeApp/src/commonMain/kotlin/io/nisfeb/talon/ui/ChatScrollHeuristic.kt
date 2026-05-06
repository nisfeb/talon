package io.nisfeb.talon.ui

/**
 * Pure decision function for the chat-list auto-scroll heuristic
 * extracted from `DmChatScreen` so it has tests instead of an
 * "I think this works" UI smoke. Two scroll paths:
 *
 *  - **Inbound**: a peer sent us a message; we scroll to the bottom
 *    only if the user was already near it (`firstVisibleItemIndex
 *    <= 12`). Reading older history isn't yanked away.
 *  - **Self-send**: the user hit send; their optimistic upsert is
 *    landing. We scroll unconditionally — their message belongs in
 *    view regardless of where they were scrolled. The baseline
 *    captures the row count at the moment of `forceBottomTick`,
 *    and we snap once `rows.size` grows past it.
 *
 * The function returns both the decision and the next baseline (so
 * the caller can clear it after a successful self-send catch-up).
 */
data class ScrollDecision(
    val scrollToBottom: Boolean,
    val nextBaseline: Int?,
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
 */
fun decideAutoScroll(
    rowsSize: Int,
    newestId: String?,
    lastNewestId: String?,
    lastSize: Int,
    firstVisibleItemIndex: Int,
    pendingSendBaselineSize: Int?,
): ScrollDecision {
    // Self-send catch-up: when our optimistic upsert has landed (rows
    // grew past the baseline), scroll regardless of position — the
    // user explicitly hit send.
    if (pendingSendBaselineSize != null && rowsSize > pendingSendBaselineSize) {
        return ScrollDecision(scrollToBottom = true, nextBaseline = null)
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
    )
}

/** Items-from-bottom threshold for the inbound auto-scroll. Past
 *  this, the user is reading history and shouldn't be yanked. */
private const val NEAR_BOTTOM_THRESHOLD = 12
