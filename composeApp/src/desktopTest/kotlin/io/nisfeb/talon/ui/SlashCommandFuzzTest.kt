package io.nisfeb.talon.ui

import io.nisfeb.talon.urbit.Fuzz
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Property-style fuzzing for the new slash-command parsers + helpers.
 * The invariants matter more than the test count: any uncaught throw
 * crashes the composer when the user types unusual input, so "never
 * throws on arbitrary strings" is the load-bearing property.
 *
 * Seeds are logged on failure — copy a seed into the test to reproduce.
 */
class SlashCommandFuzzTest {

    private val ITERATIONS = 1_000
    // Deterministic — change to System.currentTimeMillis() to explore.
    private val SEED = 1_000L

    // ─── parseSlash + detectSlashTrigger never throw ────────────────

    @Test
    fun `parseSlash never throws on arbitrary strings`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            parseSlash(Fuzz.randomString(rnd, maxLen = 200))
        }
    }

    @Test
    fun `detectSlashTrigger never throws on arbitrary cursor positions`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val text = Fuzz.randomString(rnd, maxLen = 100)
            // Probe boundary conditions plus interior + out-of-bounds.
            val cursors = listOf(
                -1, 0, 1, text.length / 2, text.length - 1,
                text.length, text.length + 1, Int.MAX_VALUE,
            )
            for (c in cursors) detectSlashTrigger(text, c)
        }
    }

    // ─── parseCalText invariants ────────────────────────────────────

    @Test
    fun `parseCalText never throws on arbitrary strings`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            parseCalText(Fuzz.randomString(rnd, maxLen = 200))
        }
    }

    @Test
    fun `parseCalText Ok result always has end greater than or equal to start`() {
        // The screen renders start..end and would crash / show garbage
        // if end < start. Pin that on every successful parse.
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val r = parseCalText(Fuzz.randomString(rnd, maxLen = 200))
            if (r is CalParseResult.Ok) {
                assertTrue(
                    "end (${r.end}) must be >= start (${r.start}) — title=${r.title}",
                    !r.end.before(r.start),
                )
            }
        }
    }

    @Test
    fun `parseTimeToken never throws on arbitrary strings`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            parseTimeToken(Fuzz.randomString(rnd, maxLen = 50))
        }
    }

    @Test
    fun `parseTimeToken result always has clamped hour and minute`() {
        // clampHM enforces h in 0..23 and m in 0..59 — verify no
        // out-of-range value escapes.
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val tr = parseTimeToken(Fuzz.randomString(rnd, maxLen = 50)) ?: return@run
            assertTrue("start.h=${tr.start.h}", tr.start.h in 0..23)
            assertTrue("start.m=${tr.start.m}", tr.start.m in 0..59)
            tr.end?.let {
                assertTrue("end.h=${it.h}", it.h in 0..23)
                assertTrue("end.m=${it.m}", it.m in 0..59)
            }
        }
    }

    // ─── parseTzInput invariants ────────────────────────────────────

    @Test
    fun `parseTzInput never throws on arbitrary strings`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            parseTzInput(Fuzz.randomString(rnd, maxLen = 100))
        }
    }

    @Test
    fun `resolveZoneToken never throws on arbitrary strings`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            resolveZoneToken(Fuzz.randomString(rnd, maxLen = 100))
        }
    }

    @Test
    fun `parseTzInput Ok result is anchored to today or tomorrow`() {
        // The impl bumps to tomorrow if the resolved instant is before
        // now. The result must therefore be >= now within a day window.
        // Allow a tiny clock-skew slack since parseTzInput captures
        // its own `now` snapshot internally.
        val now = java.util.Date()
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val r = parseTzInput(Fuzz.randomString(rnd, maxLen = 100), now)
            if (r is TzParseResult.Ok) {
                assertFalse(
                    "instant ${r.instant} must not be before now ($now)",
                    r.instant.before(now),
                )
                val msInTwoDays = 2L * 24L * 60L * 60L * 1000L
                assertTrue(
                    "instant ${r.instant} more than 2 days past now",
                    r.instant.time - now.time < msInTwoDays,
                )
            }
        }
    }

    // ─── parsePollInput invariants ──────────────────────────────────

    @Test
    fun `parsePollInput never throws on arbitrary strings`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            parsePollInput(Fuzz.randomString(rnd, maxLen = 600))
        }
    }

    @Test
    fun `parsePollInput Ok always honors option count + length caps`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val r = parsePollInput(Fuzz.randomString(rnd, maxLen = 600))
            if (r is PollParseResult.Ok) {
                assertTrue(
                    "options.size=${r.poll.options.size}",
                    r.poll.options.size in 2..MAX_POLL_OPTIONS,
                )
                assertTrue(
                    "question length=${r.poll.question.length}",
                    r.poll.question.length <= 240,
                )
                for (o in r.poll.options) {
                    assertTrue("option length=${o.length}", o.length <= 120)
                }
            }
        }
    }

    @Test
    fun `encodePollTag and decodePollTag round-trip on parsed polls`() {
        // Generates inputs that PARSE successfully, then verifies the
        // encode/decode cycle preserves question + options exactly.
        // Catches escape-rule regressions (e.g. an option containing
        // `|` accidentally splitting into two on decode).
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val parsed = parsePollInput(Fuzz.randomString(rnd, maxLen = 600))
            if (parsed is PollParseResult.Ok) {
                val tag = encodePollTag(parsed.poll)
                val decoded = decodePollTag(tag)
                    ?: throw AssertionError(
                        "decodePollTag returned null for self-encoded tag: $tag",
                    )
                if (decoded.question != parsed.poll.question) {
                    throw AssertionError(
                        "question mismatch: encoded=${parsed.poll.question} " +
                            "decoded=${decoded.question} tag=$tag",
                    )
                }
                if (decoded.options != parsed.poll.options) {
                    throw AssertionError(
                        "options mismatch: encoded=${parsed.poll.options} " +
                            "decoded=${decoded.options} tag=$tag",
                    )
                }
            }
        }
    }

    // ─── slash-command spec: filter never crashes ──────────────────

    @Test
    fun `filterSlashCommands never throws and result is a subset of catalog`() {
        Fuzz.run(ITERATIONS, SEED) { rnd, _ ->
            val out = filterSlashCommands(Fuzz.randomString(rnd, maxLen = 50))
            assertTrue(
                "filter returned items not in the catalog: $out",
                out.all { it in SLASH_COMMANDS },
            )
        }
    }
}
