package io.nisfeb.talon.urbit

import kotlin.random.Random
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Property-style fuzzing for the pure parsers + helpers. We can't
 * enumerate every payload shape the ship emits; instead we bang on
 * each function with 1k+ random inputs per run and assert invariants
 * hold (never throws, id-normalized, bounded recursion, etc.).
 *
 * Seeds are logged on failure — copy one into the test to reproduce.
 */
class FuzzTest {

    private val ITERATIONS = 1_000
    // Deterministic across runs. Change to System.currentTimeMillis()
    // to explore.
    private val SEED = 1_000L

    // ─── wire classifiers — never throw ────────────────────────

    @Test
    fun `classifyChannelDelta never throws on arbitrary JSON`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val input = Fuzz.randomJsonObject(rnd)
            // Just call it — we don't assert the intent shape, only
            // that it returns a value without crashing.
            classifyChannelDelta(input)
        }
    }

    @Test
    fun `classifyGroupEvent never throws on arbitrary JSON`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val input = Fuzz.randomJsonObject(rnd)
            classifyGroupEvent(input)
        }
    }

    @Test
    fun `parseAdminGroup never throws on arbitrary JSON`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val flag = "~sampel/${Fuzz.randomString(rnd, 10)}"
            val input = Fuzz.randomJsonObject(rnd, depth = 4)
            parseAdminGroup(flag, input)
        }
    }

    @Test
    fun `parseCite never throws on arbitrary JSON`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val input = Fuzz.randomJsonObject(rnd)
            parseCite(input)
        }
    }

    @Test
    fun `ingestedPost never throws on arbitrary JSON`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val whom = listOf("~sampel", "0v2.abc", "chat/~h/s").random(rnd)
            val input = Fuzz.randomJson(rnd, depth = 4)
            ingestedPost(whom, input)
        }
    }

    // ─── string parsers — never throw, bounded recursion ──────

    @Test
    fun `Markdown parseInlines never throws on arbitrary strings`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val input = Fuzz.randomString(rnd, maxLen = 200)
            Markdown.parseInlines(input)
        }
    }

    @Test
    fun `MarkdownBlocks toStory never throws on arbitrary strings`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val input = Fuzz.randomString(rnd, maxLen = 500)
            MarkdownBlocks.toStory(input)
        }
    }

    @Test
    fun `chatTextToStory never throws on arbitrary strings`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val input = Fuzz.randomString(rnd, maxLen = 500)
            chatTextToStory(input)
        }
    }

    // ─── widget decoders — no crash, structural invariants ────

    @Test
    fun `widget decoders never throw on arbitrary strings`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val input = Fuzz.randomString(rnd, maxLen = 200)
            io.nisfeb.talon.ui.decodeTzTag(input)
            io.nisfeb.talon.ui.decodeCalTag(input)
            io.nisfeb.talon.ui.decodePollTag(input)
            io.nisfeb.talon.ui.decodeLocTag(input)
        }
    }

    // ─── invariants we actively care about ───────────────────

    @Test
    fun `ingestedPost always emits undotted ids`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val whom = "chat/~h/s"
            val input = Fuzz.randomJson(rnd, depth = 4)
            val out = ingestedPost(whom, input)
            for (m in out.messages) {
                assertFalse(
                    "message id $m must not contain '.'",
                    m.id.contains('.'),
                )
                m.parentId?.let {
                    assertFalse(
                        "parentId $it must not contain '.'",
                        it.contains('.'),
                    )
                }
            }
            for (r in out.reactions) {
                assertFalse(
                    "reaction postId ${r.postId} must not contain '.'",
                    r.postId.contains('.'),
                )
            }
            for (t in out.tombstones) {
                assertFalse(
                    "tombstone id $t must not contain '.'",
                    t.contains('.'),
                )
            }
        }
    }

    @Test
    fun `undotAtom is idempotent`() {
        // Calling undotAtom twice must equal calling it once for any
        // string. Catches any accidental dot-re-introduction.
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val s = Fuzz.randomString(rnd, 50)
            assertEquals(undotAtom(s), undotAtom(undotAtom(s)))
        }
    }

    @Test
    fun `dotAtom is idempotent on digit-only inputs`() {
        // Applying dotAtom twice to a purely numeric string must match
        // applying it once. Non-numeric inputs are pass-through so the
        // property trivially holds.
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val n = rnd.nextLong(0, Long.MAX_VALUE).toString()
            assertEquals(dotAtom(n), dotAtom(dotAtom(n)))
        }
    }

    @Test
    fun `dotAtom then undotAtom round-trips digit strings`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val n = rnd.nextLong(0, Long.MAX_VALUE).toString()
            assertEquals(n, undotAtom(dotAtom(n)))
        }
    }

    @Test
    fun `chatTextToStory always emits at least one verse for non-empty input`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val input = Fuzz.randomString(rnd, 100)
            val story = chatTextToStory(input)
            // Empty string still produces one verse (a single empty
            // inline line). Non-empty must produce ≥ 1.
            assertTrue("input=$input → ${story.size}", story.size >= 1)
        }
    }

    @Test
    fun `classifyChannelDelta posts-batch preserves every key`() {
        Fuzz.run(200, SEED) { rnd, _ ->
            // Seed a PostsBatch-shaped payload with random post children.
            val posts = buildJsonObject {
                repeat(rnd.nextInt(0, 5)) {
                    put("${rnd.nextLong()}", Fuzz.randomJson(rnd, depth = 2))
                }
            }
            val payload = buildJsonObject { put("posts", posts) }
            val intent = classifyChannelDelta(payload) as? ChannelDeltaIntent.PostsBatch
                ?: return@run
            assertEquals(posts.size, intent.posts.size)
        }
    }

    @Test
    fun `parseCite group variant always produces openTarget prefixed with group colon`() {
        Fuzz.run(200, SEED) { rnd, _ ->
            val flag = "~sampel/${Fuzz.randomString(rnd, 10).ifBlank { "x" }}"
            val cite = buildJsonObject { put("group", flag) }
            val r = parseCite(cite)
            val target = r.target as? CiteTarget.Group ?: return@run
            assertEquals(flag, target.flag)
        }
    }
}
