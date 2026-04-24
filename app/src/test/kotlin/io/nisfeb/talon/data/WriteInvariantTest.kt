package io.nisfeb.talon.data

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the "no dot ever reaches the DB" invariant at the boundary.
 *
 * This class is our bulwark against the DM-double-posting bug, which
 * has bitten us twice: once via applyChatDelta storing dotted ids while
 * bootstrap stored undotted, and once from a new ingest path we'll
 * invent in the future. Every write to `messages` / `reactions` now
 * flows through the DAO's public `upsert` which strips dots via
 * `.normalized()` before delegating to the @Upsert implementation —
 * these tests lock that helper's behavior across every id shape we
 * expect (and a lot of garbage).
 */
class WriteInvariantTest {

    private fun msg(
        id: String = "170.141.184.507.933.044.937.549.665.940.933.705.728",
        parentId: String? = null,
    ): MessageEntity = MessageEntity(
        whom = "~sampel",
        id = id,
        author = "~sampel",
        sentMs = 0L,
        contentJson = "[]",
        kind = "/chat",
        parentId = parentId,
    )

    // ─── MessageEntity.normalized() ──────────────────────────────

    @Test
    fun `dotted id is stripped`() {
        val out = msg(id = "170.141.184.507.933.044.937.549.665.940.933.705.728").normalized()
        assertEquals("170141184507933044937549665940933705728", out.id)
    }

    @Test
    fun `dotted parentId is stripped`() {
        val out = msg(
            id = "170141184507933044937549665940933705728",
            parentId = "170.141.184.507.933.044.937.549.665.940.933.705.729",
        ).normalized()
        assertEquals("170141184507933044937549665940933705729", out.parentId)
    }

    @Test
    fun `both id and parentId normalized in one pass`() {
        val out = msg(
            id = "170.141.184.507.933.044.937.549.665.940.933.705.728",
            parentId = "170.141.184.507.933.044.937.549.665.940.933.705.729",
        ).normalized()
        assertEquals("170141184507933044937549665940933705728", out.id)
        assertEquals("170141184507933044937549665940933705729", out.parentId)
    }

    @Test
    fun `undotted entity is returned unchanged by identity`() {
        // Common case — optimistic skip so we don't allocate a copy
        // for every upsert. Identity-equal (`assertSame`) locks the
        // optimization; swapping it for an always-copy would
        // silently regress perf under SSE flurry.
        val clean = msg(id = "170141184507933044937549665940933705728")
        assertSame(clean, clean.normalized())
    }

    @Test
    fun `null parentId stays null`() {
        val out = msg(id = "170.141.184.507", parentId = null).normalized()
        assertEquals(null, out.parentId)
    }

    @Test
    fun `dm-style author-prefixed id keeps author and strips only the da`() {
        // Tlon DMs key posts as "~author/<dotted-da>" — the tilde /
        // slash must survive; only dots inside the @da get stripped.
        val out = msg(id = "~ricsul-bilwyt-dozzod-nisfeb/170.141.184.507").normalized()
        assertEquals("~ricsul-bilwyt-dozzod-nisfeb/170141184507", out.id)
    }

    @Test
    fun `local_ optimistic id is untouched`() {
        val out = msg(id = "local_abc123").normalized()
        assertEquals("local_abc123", out.id)
    }

    @Test
    fun `normalized is idempotent`() {
        val once = msg(id = "170.141.184.507").normalized()
        val twice = once.normalized()
        assertEquals(once.id, twice.id)
        // Second call hits the fast path (no dot present) so should
        // return the same instance.
        assertSame(once, twice)
    }

    @Test
    fun `only id and parentId fields are rewritten`() {
        // The rest of the record is preserved — catches any accidental
        // broader copy {} that wipes unrelated fields.
        val original = MessageEntity(
            whom = "~peer",
            id = "~a/1.2.3",
            author = "~a",
            sentMs = 42L,
            contentJson = """["hello"]""",
            kind = "/chat",
            isDeleted = true,
            parentId = "~a/4.5.6",
            title = "a title",
            image = "an image",
        )
        val out = original.normalized()
        assertEquals("~a/123", out.id)
        assertEquals("~a/456", out.parentId)
        assertEquals(original.whom, out.whom)
        assertEquals(original.author, out.author)
        assertEquals(original.sentMs, out.sentMs)
        assertEquals(original.contentJson, out.contentJson)
        assertEquals(original.kind, out.kind)
        assertEquals(original.isDeleted, out.isDeleted)
        assertEquals(original.title, out.title)
        assertEquals(original.image, out.image)
    }

    // ─── ReactionEntity.normalized() ─────────────────────────────

    @Test
    fun `reaction postId gets undotted`() {
        val r = ReactionEntity(
            whom = "~sampel",
            postId = "170.141.184.507",
            author = "~sampel",
            emoji = ":+1:",
        )
        val out = r.normalized()
        assertEquals("170141184507", out.postId)
    }

    @Test
    fun `clean reaction passes through unchanged by identity`() {
        val r = ReactionEntity(
            whom = "~sampel",
            postId = "170141184507",
            author = "~sampel",
            emoji = ":+1:",
        )
        assertSame(r, r.normalized())
    }

    // ─── fuzz: no dot ever survives normalization ────────────────

    @Test
    fun `normalized output never contains a dot — fuzz`() {
        // Property: for any id / parentId shape the wire could
        // conceivably throw at us, output has zero dots. Exercising a
        // thousand random inputs is cheap and catches new wire
        // formats we haven't explicitly handled.
        val rnd = Random(42)
        repeat(1_000) {
            val id = randomIdLike(rnd)
            val parent = if (rnd.nextBoolean()) randomIdLike(rnd) else null
            val out = msg(id = id, parentId = parent).normalized()
            assertFalse(
                "id retained a dot after normalize: $id → ${out.id}",
                out.id.contains('.'),
            )
            assertTrue(
                "parentId retained a dot after normalize: $parent → ${out.parentId}",
                out.parentId?.contains('.') != true,
            )
        }
    }

    private fun randomIdLike(rnd: Random): String {
        // Mix of shapes: raw @ud, dotted @ud, author-prefixed, mangled.
        return when (rnd.nextInt(6)) {
            0 -> rnd.nextLong(0, Long.MAX_VALUE).toString()
            1 -> {
                val n = rnd.nextLong(0, Long.MAX_VALUE).toString()
                val sb = StringBuilder()
                var i = n.length
                while (i > 3) {
                    sb.insert(0, "." + n.substring(i - 3, i))
                    i -= 3
                }
                sb.insert(0, n.substring(0, i))
                sb.toString()
            }
            2 -> "~sampel/${rnd.nextLong(0, Long.MAX_VALUE)}"
            3 -> "~sampel/1.234.567.890"
            4 -> "local_${rnd.nextLong(0, Long.MAX_VALUE)}"
            else -> buildString {
                repeat(rnd.nextInt(0, 20)) {
                    append((rnd.nextInt(0x20, 0x7F)).toChar())
                }
            }
        }
    }
}
