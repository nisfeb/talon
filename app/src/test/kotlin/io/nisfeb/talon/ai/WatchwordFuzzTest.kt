package io.nisfeb.talon.ai

import io.nisfeb.talon.urbit.Fuzz
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fuzzing for the pure helpers in Watchwords.kt. Same pattern as
 * [io.nisfeb.talon.urbit.FuzzTest] — 1k seeded inputs per test, asserting
 * invariants (never throws, idempotence, charset, etc.).
 *
 * Failures print the seed; rerun with the same seed to reproduce.
 */
class WatchwordFuzzTest {

    private val ITERATIONS = 1_000
    private val SEED = 1_000L

    // ─── matchesWordBoundary ──────────────────────────────────

    @Test
    fun `matchesWordBoundary never throws on arbitrary strings`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val haystack = Fuzz.randomString(rnd, maxLen = 200)
            val needle = Fuzz.randomString(rnd, maxLen = 40)
            matchesWordBoundary(haystack, needle)
        }
    }

    @Test
    fun `matchesWordBoundary is deterministic`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val haystack = Fuzz.randomString(rnd, maxLen = 200)
            val needle = Fuzz.randomString(rnd, maxLen = 40)
            assertEquals(
                matchesWordBoundary(haystack, needle),
                matchesWordBoundary(haystack, needle),
            )
        }
    }

    @Test
    fun `matchesWordBoundary is case-insensitivity-invariant`() {
        // Lowercasing or uppercasing the inputs must not change the result —
        // the function is documented as case-insensitive.
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val haystack = Fuzz.randomString(rnd, maxLen = 200)
            val needle = Fuzz.randomString(rnd, maxLen = 40)
            val original = matchesWordBoundary(haystack, needle)
            assertEquals(
                original,
                matchesWordBoundary(haystack.uppercase(), needle.lowercase()),
            )
            assertEquals(
                original,
                matchesWordBoundary(haystack.lowercase(), needle.uppercase()),
            )
        }
    }

    @Test
    fun `matchesWordBoundary returns false for empty needle`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val haystack = Fuzz.randomString(rnd, maxLen = 200)
            assertFalse(matchesWordBoundary(haystack, ""))
        }
    }

    @Test
    fun `matchesWordBoundary on empty haystack matches only empty needle`() {
        // Empty needle is the false-on-empty-needle short-circuit, so empty
        // haystack should always be false.
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val needle = Fuzz.randomString(rnd, maxLen = 40)
            assertFalse(matchesWordBoundary("", needle))
        }
    }

    @Test
    fun `matchesWordBoundary embedded in non-letter padding always matches`() {
        // If we wrap the needle in spaces, it's unambiguously a word — the
        // function must agree, modulo the empty-needle short-circuit.
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val needle = Fuzz.randomString(rnd, maxLen = 40)
            if (needle.isEmpty()) return@run
            val haystack = "   $needle   "
            assertTrue(
                "padded haystack must match: needle=<$needle>",
                matchesWordBoundary(haystack, needle),
            )
        }
    }

    // ─── sanitizeTerm ─────────────────────────────────────────

    @Test
    fun `sanitizeTerm never throws`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val input = Fuzz.randomString(rnd, maxLen = 200)
            sanitizeTerm(input)
        }
    }

    @Test
    fun `sanitizeTerm is idempotent`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val input = Fuzz.randomString(rnd, maxLen = 200)
            val once = sanitizeTerm(input)
            val twice = sanitizeTerm(once)
            assertEquals("input=<$input>", once, twice)
        }
    }

    @Test
    fun `sanitizeTerm output is lowercase ascii alphanumeric or underscore`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val input = Fuzz.randomString(rnd, maxLen = 200)
            val out = sanitizeTerm(input)
            for (c in out) {
                assertTrue(
                    "input=<$input> out=<$out> bad char=<$c>",
                    c == '_' || (c in 'a'..'z') || (c in '0'..'9'),
                )
            }
        }
    }

    @Test
    fun `sanitizeTerm output has no leading or trailing underscores`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val input = Fuzz.randomString(rnd, maxLen = 200)
            val out = sanitizeTerm(input)
            if (out.isEmpty()) return@run
            assertFalse("input=<$input> out=<$out>", out.startsWith('_'))
            assertFalse("input=<$input> out=<$out>", out.endsWith('_'))
        }
    }

    @Test
    fun `sanitizeTerm output has no consecutive underscores`() {
        // The regex collapses any run of non-alphanumerics into a single
        // underscore — so two underscores in a row is a regression.
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val input = Fuzz.randomString(rnd, maxLen = 200)
            val out = sanitizeTerm(input)
            assertFalse(
                "input=<$input> out=<$out>",
                out.contains("__"),
            )
        }
    }

    @Test
    fun `sanitizeTerm is case-insensitive on input`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val input = Fuzz.randomString(rnd, maxLen = 200)
            assertEquals(
                sanitizeTerm(input),
                sanitizeTerm(input.uppercase()),
            )
            assertEquals(
                sanitizeTerm(input),
                sanitizeTerm(input.lowercase()),
            )
        }
    }
}
