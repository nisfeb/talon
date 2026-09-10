package io.nisfeb.talon.mail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The tree, and in particular the path, which is not a layout detail:
 * it is what a reply or forward discloses. A reader that shows a
 * different set from the one that travels is worse than one that shows
 * nothing.
 */
class MailTreeTest {

    private fun msg(
        id: String,
        prev: String? = null,
        sent: Long = 0,
        verdict: Verdict = Verdict.VERIFIED,
    ) = MailMessage(id = id, from = "~zod", prev = prev, sent = sent, verdict = verdict)

    @Test
    fun `two replies to one message are a branch, not a sequence`() {
        val forest = threadTree(
            listOf(
                msg("root", sent = 1),
                msg("a", prev = "root", sent = 2),
                msg("b", prev = "root", sent = 3),
            ),
        )
        assertEquals(1, forest.size)
        assertEquals(listOf("a", "b"), forest[0].children.map { it.message.id })
        assertTrue(branches(forest))
    }

    @Test
    fun `a straight thread does not branch`() {
        val forest = threadTree(
            listOf(msg("root", sent = 1), msg("a", prev = "root", sent = 2)),
        )
        assertFalse(branches(forest))
    }

    @Test
    fun `a path carries the line to a message and no sibling of it`() {
        val forest = threadTree(
            listOf(
                msg("root", sent = 1),
                msg("mine", prev = "root", sent = 2),
                msg("theirs", prev = "root", sent = 3),
                msg("deep", prev = "mine", sent = 4),
            ),
        )
        val path = pathTo(forest, "deep").map { it.id }
        assertEquals(listOf("root", "mine", "deep"), path)
        assertFalse(
            "theirs" in path,
            "a side exchange on another branch must not travel with a forward",
        )
    }

    @Test
    fun `a path to the root is the root alone`() {
        val forest = threadTree(listOf(msg("root"), msg("a", prev = "root", sent = 2)))
        assertEquals(listOf("root"), pathTo(forest, "root").map { it.id })
    }

    @Test
    fun `an id the thread does not hold carries nothing`() {
        val forest = threadTree(listOf(msg("root")))
        assertEquals(emptyList(), pathTo(forest, "elsewhere"))
    }

    @Test
    fun `a message whose parent we cannot read is shown and marked`() {
        // The parent is a copy this build refuses, so it is not in the
        // thread. Dropping the child would lose mail; drawing it as an
        // ordinary root would claim it started the conversation.
        val forest = threadTree(listOf(msg("root", sent = 1), msg("child", prev = "gone", sent = 2)))
        assertEquals(2, forest.size)
        assertTrue(forest.single { it.message.id == "child" }.orphaned)
        assertFalse(forest.single { it.message.id == "root" }.orphaned)
    }

    @Test
    fun `siblings are ordered by send time, and ties do not shuffle`() {
        val forest = threadTree(
            listOf(
                msg("root", sent = 1),
                msg("zzz", prev = "root", sent = 5),
                msg("aaa", prev = "root", sent = 5),
                msg("mid", prev = "root", sent = 3),
            ),
        )
        assertEquals(listOf("mid", "aaa", "zzz"), forest[0].children.map { it.message.id })
    }

    @Test
    fun `flatten walks depth first and reports depth`() {
        val forest = threadTree(
            listOf(
                msg("root", sent = 1),
                msg("a", prev = "root", sent = 2),
                msg("a1", prev = "a", sent = 3),
                msg("b", prev = "root", sent = 4),
            ),
        )
        assertEquals(
            listOf("root" to 0, "a" to 1, "a1" to 2, "b" to 1),
            flatten(forest).map { (n, d) -> n.message.id to d },
        )
    }

    @Test
    fun `the tip to answer is the newest honest message`() {
        val messages = listOf(
            msg("root", sent = 1),
            msg("honest", prev = "root", sent = 2),
            msg("faked", prev = "root", sent = 9, verdict = Verdict.FORGED),
        )
        assertEquals("honest", newestAnswerable(messages)?.id)
    }

    @Test
    fun `a thread of only forgeries has nothing to answer`() {
        assertEquals(null, newestAnswerable(listOf(msg("f", verdict = Verdict.FORGED))))
    }

    @Test
    fun `an empty thread is an empty forest`() {
        assertEquals(emptyList(), threadTree(emptyList()))
        assertFalse(branches(emptyList()))
    }
}
