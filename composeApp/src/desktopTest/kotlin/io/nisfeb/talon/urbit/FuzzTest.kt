package io.nisfeb.talon.urbit

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
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

    // ─── activity feed parser (rc14) ──────────────────────────

    @Test
    fun `parseActivityEventTimeMs result is always non-negative`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val time = Fuzz.randomString(rnd, 50)
            val event = Fuzz.randomJsonObject(rnd, depth = 2)
            val ms = TlonChatRepo.parseActivityEventTimeMs(time, event)
            assertTrue("got $ms for time=$time", ms >= 0)
        }
    }
}
