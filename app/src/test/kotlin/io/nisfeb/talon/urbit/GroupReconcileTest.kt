package io.nisfeb.talon.urbit

import io.nisfeb.talon.data.ChannelGroupEntity
import io.nisfeb.talon.data.GroupEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the bootstrap-time diff between locally-cached groups and the
 * live /v2/groups scry result. Every case below corresponds to a real
 * way the DB drifts out of sync while Talon is offline.
 */
class GroupReconcileTest {

    private fun g(flag: String) = GroupEntity(flag, title = null, image = null)
    private fun cg(nest: String, flag: String) =
        ChannelGroupEntity(nest = nest, groupFlag = flag, title = null)

    @Test
    fun `nothing to reconcile when local matches live`() {
        val plan = planGroupReconcile(
            existingGroups = listOf(g("~host/a"), g("~host/b")),
            existingChannels = listOf(
                cg("chat/~host/a-main", "~host/a"),
                cg("chat/~host/b-main", "~host/b"),
            ),
            liveGroupFlags = setOf("~host/a", "~host/b"),
            liveChannelNests = setOf("chat/~host/a-main", "chat/~host/b-main"),
        )
        assertEquals(emptySet<String>(), plan.deletedGroupFlags)
        assertEquals(emptySet<String>(), plan.deletedChannelNests)
    }

    @Test
    fun `group deleted by host while offline is removed`() {
        // Host deleted ~host/b. /v2/groups no longer reports it. The
        // channel under it drops too — reconcile finds both as stale.
        val plan = planGroupReconcile(
            existingGroups = listOf(g("~host/a"), g("~host/b")),
            existingChannels = listOf(
                cg("chat/~host/a-main", "~host/a"),
                cg("chat/~host/b-main", "~host/b"),
            ),
            liveGroupFlags = setOf("~host/a"),
            liveChannelNests = setOf("chat/~host/a-main"),
        )
        assertEquals(setOf("~host/b"), plan.deletedGroupFlags)
        assertEquals(setOf("chat/~host/b-main"), plan.deletedChannelNests)
    }

    @Test
    fun `group user left while offline is removed`() {
        // Same mechanism as host-delete from this ship's perspective:
        // if we're no longer a member, /v2/groups doesn't list it.
        val plan = planGroupReconcile(
            existingGroups = listOf(g("~host/a")),
            existingChannels = listOf(cg("chat/~host/a-main", "~host/a")),
            liveGroupFlags = emptySet(),
            liveChannelNests = emptySet(),
        )
        assertEquals(setOf("~host/a"), plan.deletedGroupFlags)
        assertEquals(setOf("chat/~host/a-main"), plan.deletedChannelNests)
    }

    @Test
    fun `channel removed from still-present group is dropped`() {
        // Group ~host/a still exists but its "b-side" channel was
        // removed server-side while we were offline. Group-level SSE
        // delete didn't fire for us — reconcile catches it.
        val plan = planGroupReconcile(
            existingGroups = listOf(g("~host/a")),
            existingChannels = listOf(
                cg("chat/~host/main", "~host/a"),
                cg("chat/~host/b-side", "~host/a"),
            ),
            liveGroupFlags = setOf("~host/a"),
            liveChannelNests = setOf("chat/~host/main"),
        )
        assertEquals(emptySet<String>(), plan.deletedGroupFlags)
        assertEquals(setOf("chat/~host/b-side"), plan.deletedChannelNests)
    }

    @Test
    fun `new group in live but not local is not in the deletion plan`() {
        // Reconcile only surfaces deletions; additions land via upsert
        // on the same pass. Asserts we don't accidentally flag new
        // groups as "deleted from local" (empty local ≠ deleted).
        val plan = planGroupReconcile(
            existingGroups = emptyList(),
            existingChannels = emptyList(),
            liveGroupFlags = setOf("~host/newcomer"),
            liveChannelNests = setOf("chat/~host/newcomer-general"),
        )
        assertEquals(emptySet<String>(), plan.deletedGroupFlags)
        assertEquals(emptySet<String>(), plan.deletedChannelNests)
    }

    @Test
    fun `orphan channel whose group row is already gone is still reaped`() {
        // Defensive: an earlier incomplete cleanup could leave a
        // channel_groups row whose groupFlag no longer appears locally
        // or live. Reconcile deletes it — matches `groupFlag !in liveGroupFlags`.
        val plan = planGroupReconcile(
            existingGroups = emptyList(),
            existingChannels = listOf(cg("chat/~host/orphan", "~host/gone")),
            liveGroupFlags = emptySet(),
            liveChannelNests = emptySet(),
        )
        assertEquals(emptySet<String>(), plan.deletedGroupFlags)
        assertEquals(setOf("chat/~host/orphan"), plan.deletedChannelNests)
    }
}
