package io.nisfeb.talon.ui

import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.urbit.StoryPart
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A tapped citation must land on the cited *post*, not just its
 * channel — in a thread, in a notebook, anywhere. The `da` a cite
 * carries isn't a message id, so the jump has to resolve it first,
 * and degrade sanely when the ship won't cough the post up.
 *
 * Every case uses a distinct `whom` because CiteCache is process-wide.
 */
class CiteJumpTest {

    private fun msg(whom: String, id: String) =
        MessageEntity(whom, id, "~zod", 0L, "[]", "chat")

    private class Fake(
        val local: Map<Pair<String, String>, MessageEntity> = emptyMap(),
        val remote: Map<Pair<String, String>, MessageEntity> = emptyMap(),
    ) : CiteResolver {
        var fetches = 0
        override suspend fun findLocal(whom: String, da: String) = local[whom to da]
        override suspend fun fetchPost(whom: String, da: String): MessageEntity? {
            fetches++
            return remote[whom to da]
        }
        override suspend fun fetchReply(whom: String, postDa: String, replyDa: String) =
            remote[whom to replyDa].also { fetches++ }
    }

    private fun cite(target: String?, postDa: String? = null, replyDa: String? = null) =
        StoryPart.Citation("label", target, postDa, replyDa)

    private suspend fun jump(c: StoryPart.Citation, r: CiteResolver, flag: String? = null) =
        resolveCiteJump(c, r) { flag }

    @Test
    fun `a group cite reveals the group`() = runTest {
        val j = jump(cite("group:~zod/parlor"), Fake())
        assertEquals(CiteJump.Group("~zod/parlor"), j)
    }

    @Test
    fun `a post cite resolves the da to a message id and carries the group`() = runTest {
        val whom = "chat/~zod/a"
        val f = Fake(local = mapOf((whom to "~2024.1.1") to msg(whom, "~zod/123")))
        assertEquals(
            CiteJump.Message(whom, "~zod/123", "~zod/parlor"),
            jump(cite(whom, postDa = "~2024.1.1"), f, "~zod/parlor"),
        )
        assertEquals(0, f.fetches, "a local hit must not scry")
    }

    @Test
    fun `a reply cite opens the parent thread anchored on the reply`() = runTest {
        val whom = "chat/~zod/b"
        val f = Fake(
            remote = mapOf(
                (whom to "~2024.1.1") to msg(whom, "parent"),
                (whom to "~2024.1.2") to msg(whom, "reply"),
            ),
        )
        assertEquals(
            CiteJump.Reply(whom, "parent", "reply", null),
            jump(cite(whom, postDa = "~2024.1.1", replyDa = "~2024.1.2"), f),
        )
    }

    @Test
    fun `an unresolvable reply still opens its parent thread`() = runTest {
        val whom = "chat/~zod/c"
        val f = Fake(local = mapOf((whom to "~2024.1.1") to msg(whom, "parent")))
        assertEquals(
            CiteJump.Reply(whom, "parent", null, null),
            jump(cite(whom, postDa = "~2024.1.1", replyDa = "~2024.1.9"), f),
        )
    }

    @Test
    fun `an unresolvable post still opens the channel`() = runTest {
        val whom = "chat/~zod/d"
        assertEquals(
            CiteJump.Message(whom, null, null),
            jump(cite(whom, postDa = "~2024.1.1"), Fake()),
        )
        // ...and a cite with no post at all does too.
        assertEquals(
            CiteJump.Message("chat/~zod/e", null, null),
            jump(cite("chat/~zod/e"), Fake()),
        )
    }

    @Test
    fun `a cite with no target goes nowhere`() = runTest {
        assertNull(jump(cite(null), Fake()))
    }
}
