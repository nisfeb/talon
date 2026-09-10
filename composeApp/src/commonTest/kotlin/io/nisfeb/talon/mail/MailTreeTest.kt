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

    // ---- the drawing ---------------------------------------------------

    @Test
    fun `a parent sits between its children and depth is generation`() {
        val l = layoutTree(
            listOf(
                msg("root", sent = 1),
                msg("a", prev = "root", sent = 2),
                msg("b", prev = "root", sent = 3),
            ),
        )
        val by = l.nodes.associateBy { it.message.id }
        assertEquals(0, by.getValue("root").depth)
        assertEquals(1, by.getValue("a").depth)
        assertEquals(0f, by.getValue("a").row)
        assertEquals(1f, by.getValue("b").row)
        assertEquals(0.5f, by.getValue("root").row, "centred on the span of its children")
        assertEquals(2, l.rows)
        assertEquals(2, l.cols)
    }

    @Test
    fun `a lit path is the edges a reply would carry`() {
        val l = layoutTree(
            listOf(
                msg("root", sent = 1),
                msg("mine", prev = "root", sent = 2),
                msg("theirs", prev = "root", sent = 3),
                msg("deep", prev = "mine", sent = 4),
            ),
        )
        assertEquals(setOf("root", "mine", "deep"), litPath(l, "deep"))
        assertEquals(emptySet(), litPath(l, null))
    }

    @Test
    fun `an orphan is drawn as its own root and marked`() {
        val l = layoutTree(listOf(msg("root", sent = 1), msg("child", prev = "gone", sent = 2)))
        val child = l.nodes.single { it.message.id == "child" }
        assertTrue(child.orphaned)
        assertEquals(null, child.parentId, "never silently reparented onto the real root")
        assertEquals(0, child.depth)
    }

    @Test
    fun `a cycle cannot hang the layout`() {
        // Content-addressed ids cannot really cycle, but this arrives
        // over the wire and a walk that does not terminate is a hang
        // somebody else chose for us.
        val l = layoutTree(listOf(msg("a", prev = "b"), msg("b", prev = "a")))
        assertEquals(2, l.nodes.size)
        assertTrue(l.nodes.any { it.parentId == null }, "one link is cut")
    }

    @Test
    fun `a long linear thread lays out without recursing`() {
        val chain = (0 until 2000).map { msg("m$it", prev = if (it == 0) null else "m${it - 1}", sent = it.toLong()) }
        val l = layoutTree(chain)
        assertEquals(2000, l.cols)
        assertEquals(1, l.rows)
    }

    // ---- folding -------------------------------------------------------

    @Test
    fun `folding a message hides what is under it, not itself`() {
        val forest = threadTree(
            listOf(
                msg("root", sent = 1),
                msg("a", prev = "root", sent = 2),
                msg("a1", prev = "a", sent = 3),
                msg("b", prev = "root", sent = 4),
            ),
        )
        val open = flattenVisible(forest, emptySet()).map { it.node.message.id }
        assertEquals(listOf("root", "a", "a1", "b"), open)

        val shut = flattenVisible(forest, setOf("a"))
        assertEquals(listOf("root", "a", "b"), shut.map { it.node.message.id })
        assertEquals(1, shut.single { it.node.message.id == "a" }.hidden)
    }

    @Test
    fun `a fold says how much it swallowed`() {
        val forest = threadTree(
            listOf(
                msg("root", sent = 1),
                msg("a", prev = "root", sent = 2),
                msg("a1", prev = "a", sent = 3),
                msg("a2", prev = "a1", sent = 4),
            ),
        )
        val v = flattenVisible(forest, setOf("root"))
        assertEquals(listOf("root"), v.map { it.node.message.id })
        assertEquals(3, v.single().hidden, "a fold that does not count is just an ending")
    }

    @Test
    fun `only a message with replies can be folded`() {
        val forest = threadTree(listOf(msg("root", sent = 1), msg("a", prev = "root", sent = 2)))
        assertEquals(setOf("root"), foldableIds(forest))
    }
}
