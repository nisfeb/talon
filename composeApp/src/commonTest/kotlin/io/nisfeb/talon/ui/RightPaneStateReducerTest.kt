package io.nisfeb.talon.ui

import io.nisfeb.talon.urbit.MediaCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure-logic tests for the right-pane state machine.
 *
 * Each transition has a test that pins its mutual-exclusion rule —
 * the bug class these are guarding against is "I forgot to clear
 * groupInfoOpenFor when I changed openChat" (rc6 audit caught three
 * such regressions across App.kt + TalonApp.kt). With the production
 * code routed through these reducer functions, a missed write site
 * is a missed test, not a runtime regression.
 */
class RightPaneStateReducerTest {

    @Test
    fun `openThread clears group info and drilldown`() {
        val s0 = RightPaneState(
            groupInfoOpenFor = "chat/~zod/x",
            groupInfoDrilldown = MediaCategory.Photo,
        )
        val s1 = RightPaneStateReducer.openThread(s0, "~zod/1.000")
        assertEquals("~zod/1.000", s1.openThreadParent)
        assertNull(s1.openThreadReplyAnchor)
        assertNull(s1.groupInfoOpenFor)
        assertNull(s1.groupInfoDrilldown)
    }

    @Test
    fun `openThread with anchor preserves the anchor`() {
        val s = RightPaneStateReducer.openThread(
            RightPaneState(),
            parentId = "~zod/1.000",
            replyAnchor = "~zod/2.000",
        )
        assertEquals("~zod/1.000", s.openThreadParent)
        assertEquals("~zod/2.000", s.openThreadReplyAnchor)
    }

    @Test
    fun `openGroupInfo clears thread state`() {
        val s0 = RightPaneState(
            openThreadParent = "~zod/1.000",
            openThreadReplyAnchor = "~zod/2.000",
        )
        val s1 = RightPaneStateReducer.openGroupInfo(s0, "chat/~zod/x")
        assertEquals("chat/~zod/x", s1.groupInfoOpenFor)
        assertNull(s1.groupInfoDrilldown)
        assertNull(s1.openThreadParent)
        assertNull(s1.openThreadReplyAnchor)
    }

    @Test
    fun `openGroupInfo also resets the drilldown sub-state`() {
        // If a previous group-info session ended in a drilldown, a
        // fresh open should land on the stats grid, not the prior
        // category.
        val s0 = RightPaneState(
            groupInfoOpenFor = "chat/~zod/old",
            groupInfoDrilldown = MediaCategory.Photo,
        )
        val s1 = RightPaneStateReducer.openGroupInfo(s0, "chat/~zod/new")
        assertEquals("chat/~zod/new", s1.groupInfoOpenFor)
        assertNull(s1.groupInfoDrilldown)
    }

    @Test
    fun `openCategory preserves the group-info anchor`() {
        val s0 = RightPaneState(groupInfoOpenFor = "chat/~zod/x")
        val s1 = RightPaneStateReducer.openCategory(s0, MediaCategory.Photo)
        assertEquals("chat/~zod/x", s1.groupInfoOpenFor)
        assertEquals(MediaCategory.Photo, s1.groupInfoDrilldown)
    }

    @Test
    fun `closeDrilldown preserves the group-info anchor`() {
        val s0 = RightPaneState(
            groupInfoOpenFor = "chat/~zod/x",
            groupInfoDrilldown = MediaCategory.Photo,
        )
        val s1 = RightPaneStateReducer.closeDrilldown(s0)
        assertEquals("chat/~zod/x", s1.groupInfoOpenFor)
        assertNull(s1.groupInfoDrilldown)
    }

    @Test
    fun `closeRightPane clears everything`() {
        val s0 = RightPaneState(
            openThreadParent = "~zod/1.000",
            openThreadReplyAnchor = "~zod/2.000",
            groupInfoOpenFor = "chat/~zod/x",
            groupInfoDrilldown = MediaCategory.Photo,
        )
        val s1 = RightPaneStateReducer.closeRightPane(s0)
        assertEquals(RightPaneState(), s1)
    }

    @Test
    fun `openConversation invalidates all per-chat right-pane state`() {
        // Regression guard for rc6 bug 4: switching chats via a
        // citation tap or DmListScreen tap was leaving openThreadParent
        // referencing the OLD chat's post id, then RightPaneContent
        // .Thread(whom = newChat, parentId = oldId) rendered an empty
        // / broken thread pane.
        val s0 = RightPaneState(
            openThreadParent = "~zod/1.000",
            groupInfoOpenFor = "chat/~zod/x",
            groupInfoDrilldown = MediaCategory.Photo,
        )
        val s1 = RightPaneStateReducer.openConversation(s0)
        assertEquals(RightPaneState(), s1)
    }

    @Test
    fun `switchShip clears all per-ship right-pane state`() {
        // Regression guard for rc6 bug 2: groupInfoOpenFor /
        // groupInfoDrilldown live outside the per-ship key block, so
        // ship switch handlers had to remember to clear them. Three
        // sites missed it; the reducer makes a missing reset
        // impossible.
        val s0 = RightPaneState(
            openThreadParent = "~zod/1.000",
            openThreadReplyAnchor = "~zod/2.000",
            groupInfoOpenFor = "chat/~zod/x",
            groupInfoDrilldown = MediaCategory.Photo,
        )
        val s1 = RightPaneStateReducer.switchShip(s0)
        assertEquals(RightPaneState(), s1)
    }

    @Test
    fun `openCategory does not affect thread state`() {
        // Defensive: openCategory should only touch drilldown, even
        // if the state is in a "shouldn't happen" hybrid (thread +
        // group-info both set). The reducer doesn't try to enforce
        // global invariants that other transitions are responsible
        // for — its job is the transition's local mutex, not state
        // sanitisation.
        val s0 = RightPaneState(
            openThreadParent = "~zod/1.000",
            groupInfoOpenFor = "chat/~zod/x",
        )
        val s1 = RightPaneStateReducer.openCategory(s0, MediaCategory.Photo)
        assertEquals("~zod/1.000", s1.openThreadParent)
        assertEquals(MediaCategory.Photo, s1.groupInfoDrilldown)
    }
}
