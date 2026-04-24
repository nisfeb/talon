package io.nisfeb.talon.urbit

import io.nisfeb.talon.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DottedIdDedupeTest {

    private fun msg(whom: String, id: String, author: String = "~x", sent: Long = 0L) =
        MessageEntity(
            whom = whom,
            id = id,
            author = author,
            sentMs = sent,
            contentJson = "[]",
            kind = "/chat",
        )

    @Test
    fun `dotted row with no undotted twin is renamed`() {
        val dotted = msg("~sampel", "170.141.184")
        val ops = planMessageDedupe(listOf(dotted), existingUndotted = emptySet())
        assertEquals(1, ops.size)
        val r = ops[0] as DedupeOp.Rename
        assertEquals("170.141.184", r.from.id)
        assertEquals("170141184", r.to.id)
        // Rest of the row is preserved.
        assertEquals(dotted.author, r.to.author)
        assertEquals(dotted.sentMs, r.to.sentMs)
    }

    @Test
    fun `dotted row with undotted twin is dropped`() {
        // Exactly the DM-duplication case: live SSE wrote dotted,
        // bootstrap/paginate wrote undotted, both coexist.
        val dotted = msg("~sampel", "170.141.184")
        val ops = planMessageDedupe(
            listOf(dotted),
            existingUndotted = setOf("~sampel" to "170141184"),
        )
        assertEquals(1, ops.size)
        val d = ops[0] as DedupeOp.Drop
        assertEquals("~sampel", d.whom)
        assertEquals("170.141.184", d.dottedId)
    }

    @Test
    fun `within one batch the second dotted row that collapses to the same clean id drops`() {
        // Shouldn't happen in practice but guards against over-
        // renaming if it ever does.
        val a = msg("~sampel", "170.141.184", sent = 10)
        val b = msg("~sampel", "170.141.184", sent = 10)  // identical, shouldn't duplicate
        val ops = planMessageDedupe(listOf(a, b), existingUndotted = emptySet())
        assertEquals(2, ops.size)
        assertTrue(ops[0] is DedupeOp.Rename)
        assertTrue(ops[1] is DedupeOp.Drop)
    }

    @Test
    fun `undotted rows are skipped (defensive — shouldn't appear in input)`() {
        val clean = msg("~sampel", "170141184")
        val ops = planMessageDedupe(listOf(clean), existingUndotted = emptySet())
        assertEquals(0, ops.size)
    }

    @Test
    fun `rows from different whoms with the same dotted id are treated independently`() {
        val rowA = msg("~alice", "1.234")
        val rowB = msg("~bob", "1.234")
        val ops = planMessageDedupe(
            listOf(rowA, rowB),
            existingUndotted = setOf("~alice" to "1234"),  // only alice has a twin
        )
        val byKind = ops.groupBy { it::class }
        val drops = byKind[DedupeOp.Drop::class].orEmpty().map { it as DedupeOp.Drop }
        val renames = byKind[DedupeOp.Rename::class].orEmpty().map { it as DedupeOp.Rename }
        assertEquals(listOf("~alice"), drops.map { it.whom })
        assertEquals(listOf("~bob"), renames.map { it.from.whom })
    }

    @Test
    fun `empty input produces no ops`() {
        assertEquals(emptyList<DedupeOp>(), planMessageDedupe(emptyList(), emptySet()))
    }
}
