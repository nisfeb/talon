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

    // ---- depth: a shape the wire can simply hand us --------------------

    @Test
    fun `a ten-thousand-message chain of self-replies does not blow the stack`() {
        // This is not a pathological input; it is a long conversation.
        // Anything recursive over it is a stack overflow somebody else
        // chooses to hand us, so every walk below is iterative — and the
        // assertions here walk iteratively too, or the test would fail
        // for the reason the fix exists.
        val depth = 10_000
        val chain = (0 until depth).map {
            msg("m$it", prev = if (it == 0) null else "m${it - 1}", sent = it.toLong())
        }
        val forest = threadTree(chain)
        assertEquals(1, forest.size, "one root, and the whole chain under it")

        var tip = forest.single()
        var walked = 1
        while (tip.children.isNotEmpty()) {
            tip = tip.children.single()
            walked++
        }
        assertEquals(depth, walked, "every message is on the one chain")
        assertEquals("m${depth - 1}", tip.message.id)

        val open = flattenVisible(forest, emptySet())
        assertEquals(depth, open.size)
        assertEquals("m4999", open[4999].node.message.id, "a straight chain flattens in order")
        assertEquals(4999, open[4999].depth, "row i is generation i")

        val shut = flattenVisible(forest, setOf("m0"))
        assertEquals(1, shut.size)
        assertEquals(depth - 1, shut.single().hidden, "folding the root counts the whole chain under it")

        val mid = flattenVisible(forest, setOf("m5000"))
        assertEquals(5001, mid.size)
        assertEquals(4999, mid.last().hidden, "a fold halfway down counts what is under it, no more")

        val path = pathTo(forest, "m${depth - 1}")
        assertEquals(depth, path.size, "a reply from the tip carries the whole chain")
        assertEquals("m0", path.first().id)
        assertEquals("m5000", path[5000].id)
        assertEquals("m${depth - 1}", path.last().id)
    }

    // ---- copies of one message -----------------------------------------
    //
    // Several grubs can share an id and differ only in signature. Each
    // rule below exists to stop a forged copy deciding something.

    private fun copy(
        id: String,
        verdict: Verdict,
        sent: Long,
        body: String = "body",
        prev: String? = null,
    ) = MailMessage(id = id, from = "~zod", prev = prev, sent = sent, body = body, verdict = verdict)

    @Test
    fun `a forgery cannot hide behind a genuine copy of the same id`() {
        val out = collapse(
            listOf(
                copy("m", Verdict.VERIFIED, sent = 5),
                copy("m", Verdict.FORGED, sent = 1),
            ),
        )
        assertEquals(1, out.size, "copies are not messages")
        assertEquals(Verdict.FORGED, out.single().verdict, "the loudest verdict wins")
    }

    @Test
    fun `the honest copy speaks, even when a forged one is newer`() {
        // `sent` is signed and the author picks it, so letting a forged
        // copy speak lets whoever poked the chain choose what it says.
        val out = collapse(
            listOf(
                copy("m", Verdict.VERIFIED, sent = 1, body = "what was written"),
                copy("m", Verdict.FORGED, sent = 9, body = "what was not"),
            ),
        )
        assertEquals("what was written", out.single().body)
        assertEquals(Verdict.FORGED, out.single().verdict, "and it is still marked")
    }

    @Test
    fun `with nothing honest the newest copy speaks`() {
        val out = collapse(
            listOf(
                copy("m", Verdict.FORGED, sent = 1, body = "older"),
                copy("m", Verdict.FORGED, sent = 9, body = "newer"),
            ),
        )
        assertEquals("newer", out.single().body)
        assertEquals(Verdict.FORGED, out.single().verdict)
    }

    @Test
    fun `the reader and the drawing cannot disagree about a node`() {
        // The bug this replaces: one kept the last copy of an id and the
        // other kept the first, so the two views could show different
        // verdicts for the same message.
        val ms = listOf(
            copy("root", Verdict.VERIFIED, sent = 1),
            copy("m", Verdict.VERIFIED, sent = 2, prev = "root"),
            copy("m", Verdict.FORGED, sent = 3, prev = "root"),
        )
        val inList = flattenVisible(threadTree(ms), emptySet()).single { it.node.message.id == "m" }
        val inTree = layoutTree(ms).nodes.single { it.message.id == "m" }
        assertEquals(Verdict.FORGED, inList.node.message.verdict)
        assertEquals(inList.node.message.verdict, inTree.message.verdict)
    }

}
