package io.nisfeb.talon.ui

import io.nisfeb.talon.urbit.MediaCategory

/**
 * Pure-data model of the right pane's mutable state. `App.kt` and
 * `TalonApp.kt` mutate these four fields through
 * [RightPaneStateReducer]'s pure transition functions instead of
 * touching them inline at every call site. The mutual-exclusion rules
 * (opening a thread closes group info, switching ships clears
 * everything, etc.) live in one tested place rather than scattered
 * across ~20 inline lambdas — the latter being how three classes of
 * state-leak bugs slipped past Phase 3 review (rc6 audit).
 *
 * The hosts (App.kt / TalonApp.kt) keep their flat backing vars but
 * route writes through `applyRightPaneState(reducer.foo(snapshot()))`
 * helpers. Adding a new transition rule is a one-touch change here
 * plus a unit test in `RightPaneStateReducerTest`.
 */
data class RightPaneState(
    val openThreadParent: String? = null,
    val openThreadReplyAnchor: String? = null,
    val groupInfoOpenFor: String? = null,
    val groupInfoDrilldown: MediaCategory? = null,
)

object RightPaneStateReducer {

    /**
     * Open a thread for [parentId] (with optional jump-to-reply
     * [replyAnchor]). Mutually excludes group info — the spec says
     * threads and group info can't both be visible.
     */
    fun openThread(
        state: RightPaneState,
        parentId: String,
        replyAnchor: String? = null,
    ): RightPaneState = state.copy(
        openThreadParent = parentId,
        openThreadReplyAnchor = replyAnchor,
        groupInfoOpenFor = null,
        groupInfoDrilldown = null,
    )

    /**
     * Open the group-info pane for [whom]. Mutually excludes thread.
     * The drilldown sub-state always resets so users land on the
     * stats grid, not a stale category from a previous open.
     */
    fun openGroupInfo(
        state: RightPaneState,
        whom: String,
    ): RightPaneState = state.copy(
        openThreadParent = null,
        openThreadReplyAnchor = null,
        groupInfoOpenFor = whom,
        groupInfoDrilldown = null,
    )

    /**
     * Drill into a media category from group info. Preserves the
     * group-info anchor so back-pop returns to the pane.
     */
    fun openCategory(
        state: RightPaneState,
        category: MediaCategory,
    ): RightPaneState = state.copy(groupInfoDrilldown = category)

    /** Back-pop from a drilldown to group info. */
    fun closeDrilldown(state: RightPaneState): RightPaneState =
        state.copy(groupInfoDrilldown = null)

    /** Close the entire right pane (X button or final back). */
    fun closeRightPane(@Suppress("UNUSED_PARAMETER") state: RightPaneState): RightPaneState =
        RightPaneState()

    /**
     * Switch to a different conversation. Invalidates everything —
     * thread parentId is keyed on the previous chat's posts, group
     * info is per-chat, drilldown is per-pane. None of these are
     * meaningful after the switch.
     */
    fun openConversation(@Suppress("UNUSED_PARAMETER") state: RightPaneState): RightPaneState =
        RightPaneState()

    /**
     * Switch ships. All four right-pane fields reference per-ship
     * post ids / channel nests / categories — none survive the
     * crossover.
     */
    fun switchShip(@Suppress("UNUSED_PARAMETER") state: RightPaneState): RightPaneState =
        RightPaneState()
}
