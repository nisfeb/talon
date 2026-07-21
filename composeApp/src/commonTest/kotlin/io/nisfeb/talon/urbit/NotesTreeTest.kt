package io.nisfeb.talon.urbit

import io.nisfeb.talon.data.NotesFolderEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The move rules matter more than they look. Re-parenting a folder under
 * its own descendant detaches that branch from the root — the rows
 * survive but nothing walking down from `/` reaches them, so the notes
 * inside disappear from the UI with no obvious way back.
 */
class NotesTreeTest {

    // /            (1)
    //   docs       (2)
    //     drafts   (3)
    //       old    (4)
    //   inbox      (5)
    private val tree = listOf(
        folder(1, null, "/"),
        folder(2, 1, "docs"),
        folder(3, 2, "drafts"),
        folder(4, 3, "old"),
        folder(5, 1, "inbox"),
    )

    private fun folder(id: Long, parent: Long?, name: String) = NotesFolderEntity(
        flag = "~z/n",
        folderId = id,
        parentFolderId = parent,
        name = name,
        createdBy = "~z",
        createdAtMs = 0,
        updatedBy = "~z",
        updatedAtMs = 0,
    )

    @Test
    fun `descendants includes the folder and everything under it`() {
        assertEquals(setOf(2L, 3L, 4L), NotesTree.descendants(tree, 2))
        assertEquals(setOf(4L), NotesTree.descendants(tree, 4))
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L), NotesTree.descendants(tree, 1))
    }

    @Test
    fun `a folder cannot move into itself or its own subtree`() {
        val ids = NotesTree.moveDestinations(tree, 2).map { it.folderId }
        assertFalse(2L in ids, "cannot move into itself")
        assertFalse(3L in ids, "cannot move into its child")
        assertFalse(4L in ids, "cannot move into its grandchild — this is the branch-detaching case")
        assertTrue(5L in ids, "a sibling is a legal destination")
    }

    @Test
    fun `the current parent is not offered`() {
        // docs already lives in root, so root is a no-op destination.
        val ids = NotesTree.moveDestinations(tree, 2).map { it.folderId }
        assertFalse(1L in ids)
        // drafts lives in docs, so docs is excluded but root is fine.
        val forDrafts = NotesTree.moveDestinations(tree, 3).map { it.folderId }
        assertFalse(2L in forDrafts)
        assertTrue(1L in forDrafts)
        assertTrue(5L in forDrafts)
    }

    @Test
    fun `paths read from the root`() {
        assertEquals("/", NotesTree.path(tree, 1))
        assertEquals("/docs", NotesTree.path(tree, 2))
        assertEquals("/docs/drafts", NotesTree.path(tree, 3))
        assertEquals("/docs/drafts/old", NotesTree.path(tree, 4))
    }

    @Test
    fun `order of the folder list does not matter`() {
        val shuffled = tree.reversed()
        assertEquals(setOf(2L, 3L, 4L), NotesTree.descendants(shuffled, 2))
        assertEquals("/docs/drafts/old", NotesTree.path(shuffled, 4))
    }

    @Test
    fun `a cycle in the data terminates instead of hanging`() {
        // Shouldn't happen, but a wedged UI is a worse failure than a
        // wrong answer, so the walk is bounded.
        val cyclic = listOf(
            folder(1, null, "/"),
            folder(2, 3, "a"),
            folder(3, 2, "b"),
        )
        NotesTree.descendants(cyclic, 2)
        NotesTree.path(cyclic, 2)
        NotesTree.moveDestinations(cyclic, 2)
    }
}
